package com.tether.auth.service;

import com.tether.auth.dto.*;
import com.tether.auth.model.Role;
import com.tether.auth.model.Tenant;
import com.tether.auth.model.UserAccount;
import com.tether.auth.repo.TenantRepository;
import com.tether.auth.repo.UserAccountRepository;
import com.tether.auth.security.IssuedToken;
import com.tether.auth.security.TokenService;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    // No 0/O or 1/I, so codes survive being read out on a call
    private static final String JOIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int JOIN_CODE_LENGTH = 8;
    private static final int MAX_SLUG_LENGTH = 50;

    private final UserAccountRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final SecureRandom random = new SecureRandom();
    /** Checked against when the email is unknown, so a miss costs the same BCrypt time as a hit. */
    private final String dummyHash;

    public AuthService(UserAccountRepository users, TenantRepository tenants,
                       PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Creates the user (and, for a new organization, the tenant) and logs them straight in. */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
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
            tenant = tenants.findByJoinCode(req.joinCode().trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(ApiException::invalidJoinCode);
            role = Role.MEMBER;
        }

        // saveAndFlush so a concurrent duplicate email hits the unique constraint here, not at commit
        UserAccount user = users.saveAndFlush(new UserAccount(
                tenant, email, passwordEncoder.encode(req.password()), req.displayName().trim(), role));
        return authResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        Optional<UserAccount> user = users.findByEmail(normalizeEmail(req.email()));
        boolean matches = passwordEncoder.matches(req.password(),
                user.map(UserAccount::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) throw ApiException.invalidCredentials();
        return authResponse(user.get());
    }

    /** Re-reads the user so a token for a deleted user (or a tenant mismatch) is rejected. */
    @Transactional(readOnly = true)
    public MeResponse me(UUID userId, UUID tenantId, Instant tokenExpiresAt) {
        UserAccount user = users.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Token does not match an active user"));
        return new MeResponse(UserResponse.from(user), TenantResponse.from(user.getTenant(), user.getRole()),
                tokenExpiresAt);
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
