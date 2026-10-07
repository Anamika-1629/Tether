package com.tether.auth.dto;

import java.time.Instant;

/** What /auth/me returns: the stored user/tenant plus when the presented token expires. */
public record MeResponse(UserResponse user, TenantResponse tenant, Instant tokenExpiresAt) {}
