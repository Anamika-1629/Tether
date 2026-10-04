package com.tether.incident.web;

import com.tether.incident.dto.*;
import com.tether.incident.model.Status;
import com.tether.incident.service.IncidentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Tenant and actor come from headers until the Auth service issues real identities:
 *   X-Tenant-Id  (default "default")
 *   X-Actor      (default "anonymous")
 */
@RestController
@RequestMapping("/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor,
            @Valid @RequestBody CreateIncidentRequest req) {
        IncidentResponse body = IncidentResponse.from(service.create(tenant, actor, req));
        return ResponseEntity.created(URI.create("/incidents/" + body.id())).body(body);
    }

    @GetMapping
    public List<IncidentResponse> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @RequestParam(required = false) Status status) {
        return service.list(tenant, status).stream().map(IncidentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public IncidentResponse get(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable UUID id) {
        return IncidentResponse.from(service.get(tenant, id));
    }

    @PatchMapping("/{id}")
    public IncidentResponse update(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIncidentRequest req) {
        return IncidentResponse.from(service.update(tenant, actor, id, req));
    }

    /** Timeline feed for the frontend. Read-only; there is deliberately no write endpoint. */
    @GetMapping("/{id}/audit")
    public List<AuditEventResponse> timeline(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable UUID id) {
        return service.timeline(tenant, id).stream().map(AuditEventResponse::from).toList();
    }
}
