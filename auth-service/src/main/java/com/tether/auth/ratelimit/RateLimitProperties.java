package com.tether.auth.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * tether.rate-limit.*
 *   login     failed logins per (client IP, email)
 *   join-code failed join-code guesses per client IP
 */
@ConfigurationProperties(prefix = "tether.rate-limit")
public record RateLimitProperties(Limit login, Limit joinCode) {

    public record Limit(int maxFailures, Duration window) {}

    public RateLimitProperties {
        if (login == null) login = new Limit(5, Duration.ofMinutes(15));
        if (joinCode == null) joinCode = new Limit(10, Duration.ofMinutes(15));
    }
}
