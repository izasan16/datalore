package com.daaspro;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.daaspro.**.mapper")
public class DaasproApplication {
    public static void main(String[] args) {
        SpringApplication.run(DaasproApplication.class, args);
    }
}
