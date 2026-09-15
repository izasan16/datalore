CREATE TABLE sql_script (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant(id),
    name        VARCHAR(128) NOT NULL,
    content     TEXT         NOT NULL DEFAULT '',
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_sql_script_tenant ON sql_script(tenant_id);
