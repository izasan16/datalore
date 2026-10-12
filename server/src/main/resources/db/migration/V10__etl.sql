-- ETL 任务配置与映射。JSON 使用 TEXT 保存，业务层统一校验后再写入。
CREATE TABLE etl_task (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenant(id),
    name                VARCHAR(128) NOT NULL,
    description         VARCHAR(500),
    source_datasource_id BIGINT      NOT NULL REFERENCES datasource(id),
    target_datasource_id BIGINT      NOT NULL REFERENCES datasource(id),
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    schedule_type       VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    once_at             TIMESTAMP,
    cron_expression     VARCHAR(128),
    schedule_end_at     TIMESTAMP,
    max_retries         INTEGER      NOT NULL DEFAULT 0,
    retry_interval_seconds INTEGER   NOT NULL DEFAULT 60,
    config_version      INTEGER      NOT NULL DEFAULT 1,
    created_by          BIGINT       NOT NULL REFERENCES sys_user(id),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_etl_task_tenant ON etl_task(tenant_id);

CREATE TABLE etl_mapping (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL REFERENCES tenant(id),
    task_id           BIGINT       NOT NULL REFERENCES etl_task(id) ON DELETE CASCADE,
    sort_order        INTEGER      NOT NULL DEFAULT 0,
    input_type        VARCHAR(16)  NOT NULL,
    source_schema     VARCHAR(128),
    source_table      VARCHAR(128),
    select_sql        TEXT,
    parameters_json   TEXT         NOT NULL DEFAULT '[]',
    target_schema     VARCHAR(128),
    target_table      VARCHAR(128) NOT NULL,
    target_table_mode VARCHAR(16)  NOT NULL DEFAULT 'CREATE',
    write_mode        VARCHAR(16)  NOT NULL DEFAULT 'APPEND',
    field_mappings_json TEXT       NOT NULL DEFAULT '[]',
    upsert_keys_json  TEXT         NOT NULL DEFAULT '[]',
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_etl_mapping_task ON etl_mapping(tenant_id, task_id, sort_order);

CREATE TABLE etl_run (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL REFERENCES tenant(id),
    task_id           BIGINT       NOT NULL REFERENCES etl_task(id),
    trigger_type      VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    current_attempt   INTEGER      NOT NULL DEFAULT 0,
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    read_rows         BIGINT       NOT NULL DEFAULT 0,
    written_rows      BIGINT       NOT NULL DEFAULT 0,
    mapping_stats_json TEXT        NOT NULL DEFAULT '{}',
    error_summary     VARCHAR(1000),
    worker_run_id     VARCHAR(128),
    parameters_json   TEXT         NOT NULL DEFAULT '{}',
    config_version    INTEGER      NOT NULL,
    log_object_path   VARCHAR(500),
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_etl_run_task ON etl_run(tenant_id, task_id, id DESC);

CREATE TABLE etl_run_attempt (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL REFERENCES tenant(id),
    run_id            BIGINT       NOT NULL REFERENCES etl_run(id) ON DELETE CASCADE,
    attempt_no        INTEGER      NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    read_rows         BIGINT       NOT NULL DEFAULT 0,
    written_rows      BIGINT       NOT NULL DEFAULT 0,
    error_summary     VARCHAR(1000),
    log_object_path   VARCHAR(500),
    UNIQUE (run_id, attempt_no)
);
CREATE INDEX idx_etl_attempt_run ON etl_run_attempt(tenant_id, run_id);
