package com.datalore;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.datalore.**.mapper")
public class DataloreApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataloreApplication.class, args);
    }
}
