package com.tether.auth.service;

import com.tether.auth.dto.*;
import com.tether.auth.model.Role;
import com.tether.auth.model.Tenant;
import com.tether.auth.model.UserAccount;
import com.tether.auth.ratelimit.AttemptLimiter;
import com.tether.auth.repo.TenantRepository;
import com.tether.auth.repo.UserAccountRepository;
import com.tether.auth.security.IssuedToken;
import com.tether.auth.security.TokenService;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // No 0/O or 1/I, so codes survive being read out on a call
    private static final String JOIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int JOIN_CODE_LENGTH = 8;
    private static final int MAX_SLUG_LENGTH = 50;

    private final UserAccountRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AttemptLimiter loginLimiter;
    private final AttemptLimiter joinCodeLimiter;
    private final MeterRegistry metrics;
    private final SecureRandom random = new SecureRandom();
    /** Checked against when the email is unknown, so a miss costs the same BCrypt time as a hit. */
    private final String dummyHash;

    public AuthService(UserAccountRepository users, TenantRepository tenants,
                       PasswordEncoder passwordEncoder, TokenService tokens,
                       @Qualifier("loginLimiter") AttemptLimiter loginLimiter,
                       @Qualifier("joinCodeLimiter") AttemptLimiter joinCodeLimiter,
                       MeterRegistry metrics) {
        this.users = users;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.loginLimiter = loginLimiter;
        this.joinCodeLimiter = joinCodeLimiter;
        this.metrics = metrics;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Creates the user (and, for a new organization, the tenant) and logs them straight in. */
    @Transactional
    public AuthResponse register(RegisterRequest req, String clientIp) {
        boolean creating = StringUtils.hasText(req.organizationName());
        boolean joining = StringUtils.hasText(req.joinCode());
        if (creating == joining) {
            throw ApiException.badRequest(
                    "Provide exactly one of organizationName (create a new organization) or joinCode (join an existing one)");
        }

        String email = normalizeEmail(req.email());
        if (users.existsByEmail(email)) throw ApiException.emailTaken();

        Tenant tenant;
        Role role;
        if (creating) {
            String name = req.organizationName().trim();
            tenant = tenants.save(new Tenant(name, uniqueSlug(name), uniqueJoinCode()));
            role = Role.OWNER;
        } else {
            tenant = findTenantByJoinCode(req.joinCode(), clientIp);
            role = Role.MEMBER;
        }

        // saveAndFlush so a concurrent duplicate email hits the unique constraint here, not at commit
        UserAccount user = users.saveAndFlush(new UserAccount(
                tenant, email, passwordEncoder.encode(req.password()), req.displayName().trim(), role));
        metrics.counter("tether.auth.registrations", "type", creating ? "new_org" : "join_code").increment();
        log.info("Registered user {} as {} of tenant {}", user.getId(), role, tenant.getId());
        return authResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req, String clientIp) {
        String email = normalizeEmail(req.email());
        String limitKey = clientIp + "|" + email;
        Duration blocked = loginLimiter.blockedFor(limitKey);
        if (!blocked.isZero()) {
            metrics.counter("tether.auth.logins", "outcome", "blocked").increment();
            log.warn("Login blocked for {} after repeated failures from {}", email, clientIp);
            throw ApiException.tooManyAttempts(blocked);
        }

        Optional<UserAccount> user = users.findByEmail(email);
        boolean matches = passwordEncoder.matches(req.password(),
                user.map(UserAccount::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            loginLimiter.recordFailure(limitKey);
            metrics.counter("tether.auth.logins", "outcome", "failure").increment();
            throw ApiException.invalidCredentials();
        }

        loginLimiter.reset(limitKey);
        metrics.counter("tether.auth.logins", "outcome", "success").increment();
        return authResponse(user.get());
    }

    /** Re-reads the user so a token for a deleted user (or a tenant mismatch) is rejected. */
    @Transactional(readOnly = true)
    public MeResponse me(UUID userId, UUID tenantId, Instant tokenExpiresAt) {
        UserAccount user = currentUser(userId, tenantId);
        return new MeResponse(UserResponse.from(user), TenantResponse.from(user.getTenant(), user.getRole()),
                tokenExpiresAt);
    }

    /** Everyone in the caller's tenant, e.g. for the "assign owner" picker. Never crosses tenants. */
    @Transactional(readOnly = true)
    public List<UserResponse> members(UUID userId, UUID tenantId) {
        currentUser(userId, tenantId);
        return users.findByTenantIdOrderByCreatedAtAsc(tenantId).stream().map(UserResponse::from).toList();
    }

    /** OWNER only. Invalidates the old code, e.g. after it was shared too widely. */
    @Transactional
    public TenantResponse rotateJoinCode(UUID userId, UUID tenantId) {
        UserAccount user = currentUser(userId, tenantId);
        if (user.getRole() != Role.OWNER) {
            throw ApiException.forbidden("Only the organization owner can rotate the join code");
        }
        Tenant tenant = user.getTenant();
        tenant.setJoinCode(uniqueJoinCode());
        log.info("Join code rotated for tenant {} by {}", tenant.getId(), user.getId());
        return TenantResponse.from(tenant, user.getRole());
    }

    private UserAccount currentUser(UUID userId, UUID tenantId) {
        return users.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Token does not match an active user"));
    }

    /** Limits guessing per client IP; a code is 8 chars from 32 symbols, so blind guessing is hopeless when capped. */
    private Tenant findTenantByJoinCode(String joinCode, String clientIp) {
        Duration blocked = joinCodeLimiter.blockedFor(clientIp);
        if (!blocked.isZero()) {
            metrics.counter("tether.auth.join_code.rejections", "reason", "blocked").increment();
            log.warn("Join-code attempts blocked for {}", clientIp);
            throw ApiException.tooManyAttempts(blocked);
        }
        Optional<Tenant> tenant = tenants.findByJoinCode(joinCode.trim().toUpperCase(Locale.ROOT));
        if (tenant.isEmpty()) {
            joinCodeLimiter.recordFailure(clientIp);
            metrics.counter("tether.auth.join_code.rejections", "reason", "invalid").increment();
            throw ApiException.invalidJoinCode();
        }
        return tenant.get();
    }

    private AuthResponse authResponse(UserAccount user) {
        IssuedToken token = tokens.issue(user);
        long expiresIn = Math.max(0, Duration.between(Instant.now(), token.expiresAt()).toSeconds());
        return new AuthResponse(token.value(), "Bearer", token.expiresAt(), expiresIn,
                UserResponse.from(user), TenantResponse.from(user.getTenant(), user.getRole()));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** "Acme Corp!" -> "acme-corp", or "acme-corp-x7k2" if that is taken. */
    private String uniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) base = "org";
        if (base.length() > MAX_SLUG_LENGTH) base = base.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");

        String slug = base;
        while (tenants.existsBySlug(slug)) {
            slug = base + "-" + randomCode(4).toLowerCase(Locale.ROOT);
        }
        return slug;
    }

    private String uniqueJoinCode() {
        String code;
        do {
            code = randomCode(JOIN_CODE_LENGTH);
        } while (tenants.existsByJoinCode(code));
        return code;
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(JOIN_CODE_ALPHABET.charAt(random.nextInt(JOIN_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
