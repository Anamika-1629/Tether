package com.tether.incident.dto;

import com.tether.incident.model.AuditEvent;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(Long id, UUID incidentId, String action, String field,
                                 String oldValue, String newValue, String actor, Instant at) {
    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(e.getId(), e.getIncidentId(), e.getAction().name(),
                e.getFieldName(), e.getOldValue(), e.getNewValue(), e.getActor(), e.getCreatedAt());
    }
}
