package com.daaspro.apipub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("api_app")
public class ApiAppEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String appKey;
    /** AES-GCM 加密后的 secret */
    private String appSecretEnc;
    private LocalDateTime createdAt;
}
