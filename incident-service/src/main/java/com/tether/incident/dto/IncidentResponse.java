package com.tether.incident.dto;

import com.tether.incident.model.Incident;
import com.tether.incident.model.Severity;
import com.tether.incident.model.Status;
import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(UUID id, String title, Status status, Severity severity,
                               String owner, String tenantId, Instant createdAt) {
    public static IncidentResponse from(Incident i) {
        return new IncidentResponse(i.getId(), i.getTitle(), i.getStatus(), i.getSeverity(),
                i.getOwner(), i.getTenantId(), i.getCreatedAt());
    }
}
