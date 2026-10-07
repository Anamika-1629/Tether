package com.tether.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tether.sync.security.JwtTokenValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenValidatorTest {

    private static final String SECRET = "dev-only-insecure-secret-change-me-0123456789";
    private static final String ISSUER = "tether-auth";

    private JwtTokenValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        validator = new JwtTokenValidator(SECRET, ISSUER, mapper);
    }

    private String createToken(String secret, String issuer, String tenantId, long expSeconds) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));

        Map<String, Object> payloadMap = Map.of(
                "iss", issuer,
                "tenantId", tenantId,
                "email", "alice@acme.test",
                "userId", UUID.randomUUID().toString(),
                "exp", expSeconds
        );
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(payloadMap));

        String content = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);

        return content + "." + signature;
    }

    @Test
    void testValidToken() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        long exp = Instant.now().getEpochSecond() + 3600;
        String token = createToken(SECRET, ISSUER, tenantId, exp);

        JwtTokenValidator.Claims claims = validator.validateToken(token);
        assertNotNull(claims);
        assertEquals(tenantId, claims.tenantId());
        assertEquals("alice@acme.test", claims.email());
    }

    @Test
    void testExpiredToken() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        long exp = Instant.now().getEpochSecond() - 3600;
        String token = createToken(SECRET, ISSUER, tenantId, exp);

        assertNull(validator.validateToken(token));
    }

    @Test
    void testWrongIssuer() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        long exp = Instant.now().getEpochSecond() + 3600;
        String token = createToken(SECRET, "untrusted-issuer", tenantId, exp);

        assertNull(validator.validateToken(token));
    }

    @Test
    void testTamperedSignature() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        long exp = Instant.now().getEpochSecond() + 3600;
        String token = createToken("a-completely-different-secret-12345678", ISSUER, tenantId, exp);

        assertNull(validator.validateToken(token));
    }
}
