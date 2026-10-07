package com.tether.auth.web;

import com.tether.auth.dto.*;
import com.tether.auth.security.TokenService;
import com.tether.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @Operation(summary = "Register",
            description = "Creates a user in a new organization (organizationName, caller becomes OWNER) "
                    + "or an existing one (joinCode, caller becomes MEMBER), and returns a token straight away.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        return service.register(req, http.getRemoteAddr());
    }

    @Operation(summary = "Log in", description = "Exchanges email + password for a signed JWT. "
            + "Repeated failures from the same client for the same email return 429.")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return service.login(req, http.getRemoteAddr());
    }

    @Operation(summary = "Current user and tenant", security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return service.me(userId(jwt), tenantId(jwt), jwt.getExpiresAt());
    }

    @Operation(summary = "Members of my organization", security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/tenant/members")
    public List<UserResponse> members(@AuthenticationPrincipal Jwt jwt) {
        return service.members(userId(jwt), tenantId(jwt));
    }

    @Operation(summary = "Rotate join code (OWNER only)", description = "The old code stops working immediately.",
            security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/tenant/join-code/rotate")
    public TenantResponse rotateJoinCode(@AuthenticationPrincipal Jwt jwt) {
        return service.rotateJoinCode(userId(jwt), tenantId(jwt));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(TokenService.CLAIM_USER_ID));
    }

    private static UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(TokenService.CLAIM_TENANT_ID));
    }
}
