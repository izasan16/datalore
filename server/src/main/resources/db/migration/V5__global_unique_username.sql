-- 用户名改为全局唯一：SaaS 登录不再区分租户标识
ALTER TABLE sys_user DROP CONSTRAINT sys_user_tenant_id_username_key;
ALTER TABLE sys_user ADD CONSTRAINT sys_user_username_key UNIQUE (username);
CREATE INDEX idx_sys_user_username ON sys_user(username);
