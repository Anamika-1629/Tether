package com.tether.incident.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets the frontend (different port) call this service directly. Tighten for production. */
@Configuration
public class CorsConfig {
    @Bean
    WebMvcConfigurer cors() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry r) {
                r.addMapping("/**").allowedOrigins("*")
                 .allowedMethods("GET", "POST", "PATCH", "OPTIONS")
                 .allowedHeaders("*");
            }
        };
    }
}
