package com.tether.incident.service;

import com.tether.incident.dto.CreateIncidentRequest;
import com.tether.incident.dto.UpdateIncidentRequest;
import com.tether.incident.model.AuditEvent;
import com.tether.incident.model.AuditEvent.Action;
import com.tether.incident.model.Incident;
import com.tether.incident.model.Status;
import com.tether.incident.repo.AuditEventRepository;
import com.tether.incident.repo.IncidentRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final AuditEventRepository audit;

    public IncidentService(IncidentRepository incidents, AuditEventRepository audit) {
        this.incidents = incidents;
        this.audit = audit;
    }

    /** The incident row and its audit row commit together or not at all. */
    @Transactional
    public Incident create(String tenantId, String actor, CreateIncidentRequest req) {
        Status status = req.status() != null ? req.status() : Status.OPEN;
        Incident saved = incidents.save(
                new Incident(req.title(), status, req.severity(), req.owner(), tenantId));

        String summary = "title=" + saved.getTitle() + ", status=" + saved.getStatus()
                + ", severity=" + saved.getSeverity() + ", owner=" + saved.getOwner();
        audit.save(new AuditEvent(saved.getId(), tenantId, Action.CREATED, null, null, summary, actor));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Incident> list(String tenantId, Status status) {
        return status == null
                ? incidents.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : incidents.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
    }

    @Transactional(readOnly = true)
    public Incident get(String tenantId, UUID id) {
        return incidents.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IncidentNotFoundException(id));
    }

    /** One audit row per field that actually changed. No-op patches write nothing. */
    @Transactional
    public Incident update(String tenantId, String actor, UUID id, UpdateIncidentRequest req) {
        Incident inc = get(tenantId, id);

        if (req.status() != null && req.status() != inc.getStatus()) {
            record(inc, actor, "status", inc.getStatus(), req.status());
            inc.setStatus(req.status());
        }
        if (req.severity() != null && req.severity() != inc.getSeverity()) {
            record(inc, actor, "severity", inc.getSeverity(), req.severity());
            inc.setSeverity(req.severity());
        }
        if (req.owner() != null && !Objects.equals(req.owner(), inc.getOwner())) {
            record(inc, actor, "owner", inc.getOwner(), req.owner());
            inc.setOwner(req.owner());
        }
        return inc; // dirty-checking flushes the update inside the same transaction
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> timeline(String tenantId, UUID id) {
        get(tenantId, id); // 404 if not in this tenant
        return audit.findByIncidentIdAndTenantIdOrderByCreatedAtAscIdAsc(id, tenantId);
    }

    private void record(Incident inc, String actor, String field, Object oldV, Object newV) {
        audit.save(new AuditEvent(inc.getId(), inc.getTenantId(), Action.UPDATED, field,
                oldV == null ? null : oldV.toString(), newV.toString(), actor));
    }
}
