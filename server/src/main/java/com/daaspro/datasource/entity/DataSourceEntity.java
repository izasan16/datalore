package com.daaspro.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("datasource")
public class DataSourceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String type;
    private Boolean builtin;
    private String host;
    private Integer port;
    private String databaseName;
    private String schemaName;
    private String username;
    private String passwordEnc;
    private Integer status;
    private LocalDateTime createdAt;
}
