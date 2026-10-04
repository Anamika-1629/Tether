CREATE TABLE incident (
    id          UUID PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    severity    VARCHAR(10)  NOT NULL,
    owner       VARCHAR(100),
    tenant_id   VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_incident_tenant ON incident (tenant_id, created_at DESC);

CREATE TABLE audit_event (
    id          BIGSERIAL PRIMARY KEY,
    incident_id UUID         NOT NULL REFERENCES incident(id),
    tenant_id   VARCHAR(100) NOT NULL,
    action      VARCHAR(20)  NOT NULL,   -- CREATED | UPDATED
    field_name  VARCHAR(50),             -- null for CREATED
    old_value   TEXT,
    new_value   TEXT,
    actor       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_audit_incident ON audit_event (incident_id, created_at);

-- Append-only enforcement at the database level: even a direct SQL
-- UPDATE/DELETE/TRUNCATE on audit_event is rejected.
CREATE FUNCTION audit_event_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_block_mutation();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_block_mutation();
