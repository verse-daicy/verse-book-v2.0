package com.sylvia.bookhouse;

import com.sylvia.bookhouse.config.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sylvia 森林书屋 v4.1 Spring Boot 后端。
 * 兼容 Node v4.0.x：API 路径/响应格式 100% 一致，端口 3000 同源。
 */
@SpringBootApplication
public class SylviaApplication {

    public static void main(String[] args) {
        // 在 Spring 启动前加载同目录 .env（系统环境变量优先，不覆盖）
        EnvLoader.loadEnv("./.env");
        SpringApplication.run(SylviaApplication.class, args);
    }
}
