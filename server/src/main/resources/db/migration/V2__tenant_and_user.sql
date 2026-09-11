CREATE TABLE tenant (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    schema_name   VARCHAR(64)  UNIQUE,              -- 注册时回填 tenant_{id}
    status        SMALLINT     NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE sys_user (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id),
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL DEFAULT '',
    role          VARCHAR(16)  NOT NULL DEFAULT 'member',
    status        SMALLINT     NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, username)
);

CREATE INDEX idx_sys_user_tenant ON sys_user(tenant_id);
