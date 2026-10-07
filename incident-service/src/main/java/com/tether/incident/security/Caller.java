package com.tether.incident.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is making the request, taken ONLY from the verified JWT (never from request headers).
 * tenantId scopes every query; actor is written to the audit log.
 */
public record Caller(String tenantId, String actor) {

    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_EMAIL = "email";
    private static final int ACTOR_MAX = 100; // audit_event.actor is VARCHAR(100)

    public static Caller from(Jwt jwt) {
        String actor = jwt.getClaimAsString(CLAIM_EMAIL);
        if (actor == null || actor.isBlank()) actor = jwt.getSubject();
        if (actor.length() > ACTOR_MAX) actor = actor.substring(0, ACTOR_MAX);
        return new Caller(jwt.getClaimAsString(CLAIM_TENANT_ID), actor);
    }
}
