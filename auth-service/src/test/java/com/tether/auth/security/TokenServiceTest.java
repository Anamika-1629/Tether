package com.tether.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tether.auth.model.Role;
import com.tether.auth.model.Tenant;
import com.tether.auth.model.UserAccount;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

class TokenServiceTest {

    private static final String SECRET = "unit-test-secret-at-least-32-bytes-long";
    private final JwtConfig config = new JwtConfig();

    private UserAccount user(UUID userId, UUID tenantId) {
        Tenant tenant = new Tenant("Acme", "acme", "ABCDEFGH");
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        UserAccount user = new UserAccount(tenant, "alice@acme.test", "hash", "Alice", Role.OWNER);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private TokenService tokenService(JwtProperties props) {
        return new TokenService(config.jwtEncoder(config.jwtSigningKey(props)), props);
    }

    private JwtDecoder decoder(JwtProperties props) {
        SecretKey key = config.jwtSigningKey(props);
        return config.jwtDecoder(key, props);
    }

    @Test
    void issuedTokenCarriesUserTenantAndExpiry() {
        JwtProperties props = new JwtProperties(SECRET, "tether-auth", Duration.ofMinutes(30));
        UUID userId = UUID.randomUUID(), tenantId = UUID.randomUUID();

        IssuedToken token = tokenService(props).issue(user(userId, tenantId));
        Jwt jwt = decoder(props).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("userId")).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("tenantId")).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("alice@acme.test");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OWNER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("tether-auth");
        assertThat(jwt.getExpiresAt()).isEqualTo(token.expiresAt());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtProperties ours = new JwtProperties(SECRET, "tether-auth", Duration.ofHours(1));
        JwtProperties theirs = new JwtProperties("a-different-secret-also-32-bytes-long!", "tether-auth", Duration.ofHours(1));
        String forged = tokenService(theirs).issue(user(UUID.randomUUID(), UUID.randomUUID())).value();

        assertThatThrownBy(() -> decoder(ours).decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtProperties ours = new JwtProperties(SECRET, "tether-auth", Duration.ofHours(1));
        JwtProperties other = new JwtProperties(SECRET, "someone-else", Duration.ofHours(1));
        String token = tokenService(other).issue(user(UUID.randomUUID(), UUID.randomUUID())).value();

        assertThatThrownBy(() -> decoder(ours).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretFailsFastAtStartup() {
        assertThatThrownBy(() -> new JwtProperties("too-short", "tether-auth", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
