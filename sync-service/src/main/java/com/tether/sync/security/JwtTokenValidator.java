package com.tether.sync.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Validates JWT tokens on WebSocket connect.
 * Verifies HMAC-SHA256 signature against shared JWT_SECRET, checks tether-auth issuer,
 * verifies expiration, and extracts tenantId.
 */
@Component
public class JwtTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidator.class);

    private final String secret;
    private final String expectedIssuer;
    private final ObjectMapper objectMapper;

    public JwtTokenValidator(
            @Value("${tether.jwt.secret:dev-only-insecure-secret-change-me-0123456789}") String secret,
            @Value("${tether.jwt.issuer:tether-auth}") String expectedIssuer,
            ObjectMapper objectMapper) {
        this.secret = secret;
        this.expectedIssuer = expectedIssuer;
        this.objectMapper = objectMapper;
    }

    public record Claims(String tenantId, String email, String userId, String sub, Instant expiresAt) {}

    public Claims validateToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        try {
            // Verify HMAC-SHA256 signature in constant time
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String unsignedPart = parts[0] + "." + parts[1];
            byte[] expectedSig = mac.doFinal(unsignedPart.getBytes(StandardCharsets.UTF_8));
            byte[] actualSig = Base64.getUrlDecoder().decode(parts[2]);

            if (!MessageDigest.isEqual(expectedSig, actualSig)) {
                return null;
            }

            // Verify header
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            JsonNode header = objectMapper.readTree(headerJson);
            if (!"HS256".equals(header.path("alg").asText())) {
                return null;
            }

            // Verify payload claims
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode payload = objectMapper.readTree(payloadJson);

            if (!expectedIssuer.equals(payload.path("iss").asText())) {
                return null;
            }

            long expSeconds = payload.path("exp").asLong(0);
            if (expSeconds <= 0 || Instant.ofEpochSecond(expSeconds).isBefore(Instant.now())) {
                return null;
            }

            String tenantId = payload.path("tenantId").asText(null);
            if (tenantId == null || tenantId.isBlank()) {
                return null;
            }

            String email = payload.path("email").asText(null);
            String userId = payload.path("userId").asText(null);
            String sub = payload.path("sub").asText(null);

            return new Claims(tenantId, email, userId, sub, Instant.ofEpochSecond(expSeconds));
        } catch (Exception e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return null;
        }
    }
}
