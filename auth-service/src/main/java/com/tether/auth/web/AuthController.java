package com.tether.auth.web;

import com.tether.auth.dto.*;
import com.tether.auth.security.TokenService;
import com.tether.auth.service.AuthService;
import jakarta.validation.Valid;
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

    /** Public. Creates a user in a new or existing tenant and returns a token straight away. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return service.register(req);
    }

    /** Public. Exchanges email + password for a signed JWT. */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return service.login(req);
    }

    /** Requires "Authorization: Bearer <token>". Handy for the frontend and for checking a token by hand. */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return service.me(
                UUID.fromString(jwt.getClaimAsString(TokenService.CLAIM_USER_ID)),
                UUID.fromString(jwt.getClaimAsString(TokenService.CLAIM_TENANT_ID)),
                jwt.getExpiresAt());
    }
}
