-- Organizations. tenants.id is the tenantId every other service scopes data by
-- (it is what the Incident Service stores in incident.tenant_id).
CREATE TABLE tenants (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(60)  NOT NULL,
    join_code   VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_tenants_slug      UNIQUE (slug),
    CONSTRAINT uq_tenants_join_code UNIQUE (join_code)
);

-- Every user belongs to exactly one tenant.
CREATE TABLE users (
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    email          VARCHAR(254) NOT NULL,   -- stored lower-cased
    password_hash  VARCHAR(100) NOT NULL,   -- BCrypt, never the raw password
    display_name   VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL,   -- OWNER | MEMBER
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_users_email  UNIQUE (email),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);
CREATE INDEX idx_users_tenant ON users (tenant_id);
