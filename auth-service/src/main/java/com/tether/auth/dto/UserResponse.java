package com.tether.auth.dto;

import com.tether.auth.model.Role;
import com.tether.auth.model.UserAccount;
import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName, Role role) {
    public static UserResponse from(UserAccount u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole());
    }
}
