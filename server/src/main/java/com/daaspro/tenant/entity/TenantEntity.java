package com.daaspro.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tenant")
public class TenantEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String slug;
    private String schemaName;
    private Integer status;
    private LocalDateTime createdAt;
}
