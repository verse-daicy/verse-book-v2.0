package com.sylvia.bookhouse.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 轻量 .env 加载（复刻 Node server.js 逻辑）：
 * - 只解析 KEY=VALUE 行，忽略注释/空行；
 * - 系统环境变量已存在时不覆盖（.env 只做兜底）。
 */
public final class EnvLoader {

    private EnvLoader() {}

    public static void loadEnv(String path) {
        try {
            Path p = Path.of(path);
            if (!Files.exists(p)) return;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String key = trimmed.substring(0, eq).trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                String val = trimmed.substring(eq + 1).trim();
                if (val.length() >= 2 && ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, val);
                }
            }
        } catch (IOException e) {
            // .env 缺失/损坏不阻塞启动
        }
    }
}
