package com.daaspro.apipub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("api_grant")
public class ApiGrantEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long appId;
    private Long apiId;
    private LocalDateTime createdAt;
}
