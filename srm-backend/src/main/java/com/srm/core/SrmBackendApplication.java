package com.srm.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.srm.core.mapper")
public class SrmBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SrmBackendApplication.class, args);
    }
}
