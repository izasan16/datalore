package com.daaspro.apipub.entity;

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
    private Long datasetId;
    /** JSON 数组：暴露列（有序） */
    private String columns;
    /** JSON 数组：允许等值过滤的列子集 */
    private String filterColumns;
    private Integer maxRows;
    /** 1 启用 0 停用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
