package com.tether.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tether.auth.model.Role;
import com.tether.auth.model.Tenant;
import java.util.UUID;

/** joinCode is only shown to the tenant's OWNER, who shares it with teammates. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantResponse(UUID id, String name, String slug, String joinCode) {
    public static TenantResponse from(Tenant t, Role viewerRole) {
        return new TenantResponse(t.getId(), t.getName(), t.getSlug(),
                viewerRole == Role.OWNER ? t.getJoinCode() : null);
    }
}
