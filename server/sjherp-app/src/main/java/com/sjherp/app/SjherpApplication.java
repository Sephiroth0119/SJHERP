package com.sjherp.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SJHERP 应用入口（唯一可启动模块）。
 */
@SpringBootApplication
public class SjherpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SjherpApplication.class, args);
    }
}
