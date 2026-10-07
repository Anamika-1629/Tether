package com.tether.auth.dto;

import java.time.Instant;

public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, long expiresIn,
                           UserResponse user, TenantResponse tenant) {}
