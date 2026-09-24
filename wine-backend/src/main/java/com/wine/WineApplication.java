package com.wine;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 新加坡红酒项目（智能分酒模式）后端启动类
 * 双单解耦架构：交易主订单 + 硬件履约单
 */
@SpringBootApplication
@MapperScan("com.wine.mapper")
@EnableScheduling
public class WineApplication {

    public static void main(String[] args) {
        SpringApplication.run(WineApplication.class, args);
    }
}
