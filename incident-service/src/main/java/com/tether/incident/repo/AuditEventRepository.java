package com.tether.incident.repo;

import com.tether.incident.model.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Extends bare Repository so delete/update methods are not even exposed. */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {
    AuditEvent save(AuditEvent event);
    List<AuditEvent> findByIncidentIdAndTenantIdOrderByCreatedAtAscIdAsc(UUID incidentId, String tenantId);
}
