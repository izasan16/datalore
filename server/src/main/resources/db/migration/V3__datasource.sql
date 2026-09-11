CREATE TABLE datasource (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id),
    name          VARCHAR(64)  NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    builtin       BOOLEAN      NOT NULL DEFAULT FALSE,
    host          VARCHAR(128),
    port          INTEGER,
    database_name VARCHAR(128),
    schema_name   VARCHAR(128),
    username      VARCHAR(64),
    password_enc  VARCHAR(512),
    status        SMALLINT     NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_datasource_tenant ON datasource(tenant_id);
