package com.datalore.apipub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("api_pub")
public class ApiPubEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    /** 全局唯一路径段，/api/open/{path} */
    private String path;
    /** TABLE=选择表发布；SQL=手写只读 SELECT */
    private String sourceType;
    /** SQL 模式的只读 SELECT（:param 命名参数） */
    private String sqlText;
    /** JSON 数组：参数定义 name/type/required/defaultValue */
    private String paramsJson;
    /** 调用凭证 Key（X-Api-Key），全局唯一 */
    private String apiKey;
    /** 调用凭证 Secret（AES-GCM 加密，X-Api-Secret） */
    private String apiSecretEnc;
    private Long datasourceId;
    private String schemaName;
    private String tableName;
    /** JSON 数组：暴露列（有序），仅 TABLE 模式 */
    private String columns;
    /** JSON 数组：允许等值过滤的列子集，仅 TABLE 模式 */
    private String filterColumns;
    private Integer maxRows;
    /** 1 启用 0 停用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
