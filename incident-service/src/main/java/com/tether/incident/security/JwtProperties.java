package com.tether.incident.security;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from tether.jwt.* (see application.properties). Fails fast on a weak secret. */
@ConfigurationProperties(prefix = "tether.jwt")
public record JwtProperties(String secret, String issuer) {

    static final int MIN_SECRET_BYTES = 32; // HS256 needs a key of at least 256 bits

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "tether.jwt.secret (JWT_SECRET) must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }
        if (issuer == null || issuer.isBlank()) issuer = "tether-auth";
    }
}
