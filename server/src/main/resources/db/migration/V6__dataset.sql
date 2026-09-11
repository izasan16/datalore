CREATE TABLE dataset (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id),
    name          VARCHAR(128) NOT NULL,
    table_name    VARCHAR(128) NOT NULL,
    datasource_id BIGINT       NOT NULL REFERENCES datasource(id),
    columns_def   JSONB        NOT NULL,
    row_count     BIGINT       NOT NULL DEFAULT 0,
    file_name     VARCHAR(256),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_dataset_tenant ON dataset(tenant_id);
