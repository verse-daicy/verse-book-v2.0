package com.sylvia.bookhouse.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 大模型配置（复刻 routes/api.js llmConfig）。
 * Key 只存在于服务端 .env；未配置时使用内置兜底 Key。
 */
@Component
public class LlmConfig {

    /** Node 端同款内置 Key（base64 混淆，非明文） */
    private static final String BUILTIN_KEY_B64 = "ZDkzNjhjMmJlNTBhNDU2MThmNmY3ZjhjY2Y0NzAwNWMuNWd0YWUwWThCMnE3MHBzZw==";
    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final String DEFAULT_MODEL = "glm-4-flash";

    private final Environment env;

    public LlmConfig(Environment env) {
        this.env = env;
    }

    public String baseUrl() {
        String v = env.getProperty("LLM_BASE_URL");
        if (v == null || v.isEmpty()) v = env.getProperty("sylvia.llm-base-url");
        if (v == null || v.isEmpty()) v = DEFAULT_BASE_URL;
        return v.replaceAll("/+$", "");
    }

    public String apiKey() {
        String v = env.getProperty("LLM_API_KEY");
        if (v == null || v.isEmpty()) v = env.getProperty("sylvia.llm-api-key");
        if (v == null || v.isEmpty()) {
            try {
                v = new String(Base64.getDecoder().decode(BUILTIN_KEY_B64), StandardCharsets.UTF_8);
            } catch (Exception e) {
                v = "";
            }
        }
        return v;
    }

    public String model() {
        String v = env.getProperty("LLM_MODEL");
        if (v == null || v.isEmpty()) v = env.getProperty("sylvia.llm-model");
        return (v == null || v.isEmpty()) ? DEFAULT_MODEL : v;
    }
}
