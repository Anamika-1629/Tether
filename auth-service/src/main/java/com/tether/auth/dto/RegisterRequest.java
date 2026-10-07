package com.tether.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Exactly one of organizationName / joinCode must be set:
 *   organizationName -> create a new tenant, caller becomes its OWNER
 *   joinCode         -> join an existing tenant as a MEMBER
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // BCrypt only uses the first 72 bytes, so longer passwords would be silently truncated
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String displayName,
        @Size(max = 100) String organizationName,
        @Size(max = 16) String joinCode) {}
