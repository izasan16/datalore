-- API 发布：把数据集（表）发布成只读 REST API；应用（appKey/Secret）授权调用
CREATE TABLE api_pub (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES tenant(id),
    name           VARCHAR(128) NOT NULL,
    path           VARCHAR(64)  NOT NULL UNIQUE,       -- 全局唯一，/api/open/{path} 直接定位
    dataset_id     BIGINT       NOT NULL REFERENCES dataset(id),
    columns        TEXT         NOT NULL,             -- JSON 数组：暴露列（有序）
    filter_columns TEXT         NOT NULL DEFAULT '[]',-- JSON 数组：允许等值过滤的列子集
    max_rows       INT          NOT NULL DEFAULT 1000,
    status         SMALLINT     NOT NULL DEFAULT 1,   -- 1 启用 0 停用
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_pub_tenant ON api_pub(tenant_id);

CREATE TABLE api_app (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES tenant(id),
    name           VARCHAR(128) NOT NULL,
    app_key        VARCHAR(64)  NOT NULL UNIQUE,
    app_secret_enc TEXT         NOT NULL,             -- AES-GCM 加密存储
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_api_app_tenant ON api_app(tenant_id);

CREATE TABLE api_grant (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT    NOT NULL REFERENCES tenant(id),
    app_id     BIGINT    NOT NULL REFERENCES api_app(id),
    api_id     BIGINT    NOT NULL REFERENCES api_pub(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (app_id, api_id)
);

CREATE INDEX idx_api_grant_tenant ON api_grant(tenant_id);
