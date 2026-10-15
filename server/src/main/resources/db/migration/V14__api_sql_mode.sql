-- API 发布改造：SQL 模式 + 每接口独立凭证；删除应用/授权概念
ALTER TABLE api_pub ADD COLUMN source_type    VARCHAR(16) NOT NULL DEFAULT 'TABLE';
ALTER TABLE api_pub ADD COLUMN sql_text       TEXT;
ALTER TABLE api_pub ADD COLUMN params_json    TEXT;
ALTER TABLE api_pub ADD COLUMN api_key        VARCHAR(64);
ALTER TABLE api_pub ADD COLUMN api_secret_enc TEXT;

-- SQL 模式不绑定具体表，放开表名非空约束
ALTER TABLE api_pub ALTER COLUMN table_name DROP NOT NULL;

-- 存量行生成 api_key（secret 置空，保存/重置时生成）
UPDATE api_pub SET api_key = 'ak_' || substr(md5(random()::text || id::text), 1, 16);
CREATE UNIQUE INDEX uk_api_pub_api_key ON api_pub(api_key);

DROP TABLE IF EXISTS api_grant;
DROP TABLE IF EXISTS api_app;
