package com.tether.auth.ratelimit;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AttemptLimiter loginLimiter(RateLimitProperties props, Clock clock) {
        return new AttemptLimiter(props.login().maxFailures(), props.login().window(), clock);
    }

    @Bean
    AttemptLimiter joinCodeLimiter(RateLimitProperties props, Clock clock) {
        return new AttemptLimiter(props.joinCode().maxFailures(), props.joinCode().window(), clock);
    }
}
