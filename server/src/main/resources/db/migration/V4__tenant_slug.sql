-- 租户 slug 独立列（登录标识；之前借用 schema_name 前缀约定）
ALTER TABLE tenant ADD COLUMN slug VARCHAR(32);
UPDATE tenant SET slug = replace(schema_name, 'tenant_', '');
ALTER TABLE tenant ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX uq_tenant_slug ON tenant(slug);
