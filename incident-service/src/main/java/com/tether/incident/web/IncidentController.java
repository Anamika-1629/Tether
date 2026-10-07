package com.tether.incident.web;

import com.tether.incident.dto.*;
import com.tether.incident.model.Status;
import com.tether.incident.security.Caller;
import com.tether.incident.service.IncidentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Every endpoint requires a Bearer JWT from auth-service. Tenant and actor are taken from the
 * verified token (see {@link Caller}); client-supplied identity headers are ignored.
 */
@RestController
@RequestMapping("/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateIncidentRequest req) {
        Caller c = Caller.from(jwt);
        IncidentResponse body = IncidentResponse.from(service.create(c.tenantId(), c.actor(), req));
        return ResponseEntity.created(URI.create("/incidents/" + body.id())).body(body);
    }

    @GetMapping
    public List<IncidentResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Status status) {
        Caller c = Caller.from(jwt);
        return service.list(c.tenantId(), status).stream().map(IncidentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return IncidentResponse.from(service.get(Caller.from(jwt).tenantId(), id));
    }

    @PatchMapping("/{id}")
    public IncidentResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIncidentRequest req) {
        Caller c = Caller.from(jwt);
        return IncidentResponse.from(service.update(c.tenantId(), c.actor(), id, req));
    }

    /** Timeline feed for the frontend. Read-only; there is deliberately no write endpoint. */
    @GetMapping("/{id}/audit")
    public List<AuditEventResponse> timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.timeline(Caller.from(jwt).tenantId(), id).stream().map(AuditEventResponse::from).toList();
    }
}
