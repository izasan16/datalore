-- Python script module: Spark / Flink lightweight editor

ALTER TABLE datasource ADD COLUMN IF NOT EXISTS extra_config TEXT;

CREATE TABLE pyscript (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id),
    name          VARCHAR(128) NOT NULL,
    datasource_id BIGINT       NOT NULL REFERENCES datasource(id),
    content       TEXT         NOT NULL DEFAULT '',
    params_json   TEXT         NOT NULL DEFAULT '[]',
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_pyscript_tenant ON pyscript(tenant_id);

CREATE TABLE pyscript_run (
    id            BIGSERIAL PRIMARY KEY,
    script_id     BIGINT       NOT NULL REFERENCES pyscript(id),
    datasource_id BIGINT       NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    remote_job_id VARCHAR(128),
    log_text      TEXT         NOT NULL DEFAULT '',
    error         TEXT,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_pyscript_run_script ON pyscript_run(script_id);

ALTER TABLE pyscript ADD CONSTRAINT fk_pyscript_ds FOREIGN KEY (datasource_id) REFERENCES datasource(id);