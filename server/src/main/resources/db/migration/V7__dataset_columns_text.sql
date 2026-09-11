-- jsonb 对 MyBatis-Plus 直接映射不友好（varchar→jsonb 需要显式 cast），
-- columns_def 结构由应用层校验，TEXT 存 JSON 字符串足够
ALTER TABLE dataset ALTER COLUMN columns_def TYPE TEXT;
