package com.daaspro.datasource.service;

import com.daaspro.common.exception.BizException;

/** 各数据源类型的 JDBC URL 与目录查询。schema/table 参数为受控值，拼接前做单引号转义。 */
public enum JdbcUrls {

    POSTGRESQL {
        @Override
        public String url(String host, int port, String db, String schema) {
            // stringtype=unspecified：字符串参数由数据库按目标列推断类型（批量 INSERT 导入依赖此行为）
            String u = "jdbc:postgresql://" + host + ":" + port + "/" + db + "?stringtype=unspecified";
            return schema == null ? u : u + "&currentSchema=" + schema;
        }
        @Override
        public int defaultPort() { return 5432; }
        @Override
        public String listTablesSql(String schema) {
            return "SELECT tablename FROM pg_tables WHERE schemaname = '" + esc(schema) + "' ORDER BY 1";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            return "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = '"
                    + esc(schema) + "' AND table_name = '" + esc(table) + "' ORDER BY ordinal_position";
        }
        @Override
        public String listSchemasSql() {
            return "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN "
                    + "('pg_catalog', 'information_schema') AND schema_name NOT LIKE 'pg\\_%' ORDER BY 1";
        }
    },
    MYSQL {
        @Override
        public String url(String host, int port, String db, String schema) {
            return "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        }
        @Override
        public int defaultPort() { return 3306; }
        @Override
        public String listTablesSql(String schema) {
            return "SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                    + esc(schema) + "' AND table_type = 'BASE TABLE' ORDER BY 1";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            return "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = '"
                    + esc(schema) + "' AND table_name = '"
                    + esc(table) + "' ORDER BY ordinal_position";
        }
        @Override
        public String listSchemasSql() {
            return "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN "
                    + "('mysql', 'sys', 'performance_schema', 'information_schema') ORDER BY 1";
        }
    },
    SQLSERVER {
        @Override
        public String url(String host, int port, String db, String schema) {
            return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + db + ";encrypt=false";
        }
        @Override
        public int defaultPort() { return 1433; }
        @Override
        public String listTablesSql(String schema) {
            return "SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                    + esc(schema) + "' AND table_type = 'BASE TABLE' ORDER BY 1";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            return "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = '"
                    + esc(schema) + "' AND table_name = '"
                    + esc(table) + "' ORDER BY ordinal_position";
        }
        @Override
        public String listSchemasSql() {
            return "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN "
                    + "('guest', 'INFORMATION_SCHEMA', 'sys', 'db_owner', 'db_accessadmin', 'db_securityadmin', "
                    + "'db_ddladmin', 'db_backupoperator', 'db_datareader', 'db_datawriter', 'db_denydatareader', "
                    + "'db_denydatawriter') ORDER BY 1";
        }
    },
    ORACLE {
        @Override
        public String url(String host, int port, String db, String schema) {
            return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + db;
        }
        @Override
        public int defaultPort() { return 1521; }
        @Override
        public String listTablesSql(String schema) {
            return "SELECT table_name FROM all_tables WHERE owner = '" + esc(schema) + "' ORDER BY 1";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            return "SELECT column_name, data_type, nullable FROM all_tab_columns WHERE owner = '" + esc(schema)
                    + "' AND table_name = '" + esc(table) + "' ORDER BY column_id";
        }
        @Override
        public String listSchemasSql() {
            return "SELECT DISTINCT owner FROM all_tables WHERE owner NOT IN ('SYS', 'SYSTEM', 'XDB', 'MDSYS', "
                    + "'CTXSYS', 'ORDSYS', 'OUTLN', 'DBSNMP', 'APPQOSSYS', 'OLAPSYS', 'ORDDATA', 'LBACSYS', "
                    + "'DVSYS', 'AUDSYS', 'GSMADMIN_INTERNAL') ORDER BY 1";
        }
        @Override
        public String primaryKeySql(String schema, String table) {
            return "SELECT cols.column_name FROM all_constraints cons"
                    + " JOIN all_cons_columns cols ON cons.constraint_name = cols.constraint_name"
                    + " AND cons.owner = cols.owner"
                    + " WHERE cons.constraint_type = 'P' AND cons.owner = '"
                    + esc(schema) + "' AND cons.table_name = '" + esc(table) + "' ORDER BY cols.position";
        }    };

    public abstract String url(String host, int port, String db, String schema);
    public abstract int defaultPort();
    public abstract String listTablesSql(String schema);
    public abstract String listColumnsSql(String schema, String table);
    public abstract String listSchemasSql();
    /** 主键列查询；information_schema 版本适用于 PG/MySQL/SQL Server，Oracle 自行覆盖。 */
    public String primaryKeySql(String schema, String table) {
        return "SELECT kcu.column_name FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name"
                + " AND tc.table_schema = kcu.table_schema"
                + " WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = '"
                + esc(schema) + "' AND tc.table_name = '" + esc(table) + "' ORDER BY kcu.ordinal_position";
    }

    public static JdbcUrls of(String type) {
        return switch (type) {
            case "postgresql" -> POSTGRESQL;
            case "mysql" -> MYSQL;
            case "sqlserver" -> SQLSERVER;
            case "oracle" -> ORACLE;
            default -> throw new BizException(400, "不支持的数据源类型: " + type);
        };
    }

    private static String esc(String s) {
        return s.replace("'", "''");
    }
}
