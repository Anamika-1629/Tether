package com.tether.incident.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Append-only audit record. @Immutable makes Hibernate skip UPDATEs;
 * a DB trigger (V1__init.sql) blocks UPDATE/DELETE/TRUNCATE at the source.
 */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    public enum Action { CREATED, UPDATED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Action action;

    @Column(name = "field_name", updatable = false)
    private String fieldName;

    @Column(name = "old_value", updatable = false)
    private String oldValue;

    @Column(name = "new_value", updatable = false)
    private String newValue;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {}

    public AuditEvent(UUID incidentId, String tenantId, Action action, String fieldName,
                      String oldValue, String newValue, String actor) {
        this.incidentId = incidentId;
        this.tenantId = tenantId;
        this.action = action;
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.actor = actor;
    }

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }

    public Long getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public String getTenantId() { return tenantId; }
    public Action getAction() { return action; }
    public String getFieldName() { return fieldName; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getActor() { return actor; }
    public Instant getCreatedAt() { return createdAt; }
}
