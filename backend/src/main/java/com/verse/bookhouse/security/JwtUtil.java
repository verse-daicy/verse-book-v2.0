package com.sylvia.bookhouse.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 手写 HS256 JWT（复刻 Node middleware/auth.js 逻辑）：
 * - payload {uid, username, exp}，其中 exp 为【毫秒】时间戳（Node 版约定，jjwt 按秒会误判过期）；
 * - 等长比较签名防时序攻击；
 * - 密钥沿用服务器 .env 的 JWT_SECRET → 老 token 无缝有效。
 */
@Component
public class JwtUtil {

    private static final long TOKEN_TTL_MS = 30L * 24 * 3600 * 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String secret;

    public JwtUtil(org.springframework.core.env.Environment env) {
        this.secret = env.getProperty("sylvia.jwt-secret", "sylvia-book-house-dev-secret-change-me");
    }

    public String sign(long uid, String username) {
        try {
            String header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("uid", uid);
            payloadMap.put("username", username);
            payloadMap.put("exp", System.currentTimeMillis() + TOKEN_TTL_MS);
            String payload = b64url(MAPPER.writeValueAsBytes(payloadMap));
            String sig = b64url(hmac(header + "." + payload));
            return header + "." + payload + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException("jwt sign failed", e);
        }
    }

    /** 校验通过返回 payload 字段（uid/username/exp），失败返回 null */
    public Map<String, Object> verify(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        String header = parts[0], payload = parts[1], sig = parts[2];
        String expected;
        try {
            expected = b64url(hmac(header + "." + payload));
        } catch (Exception e) {
            return null;
        }
        // 等长比较防时序攻击
        byte[] a = sig.getBytes(StandardCharsets.US_ASCII);
        byte[] b = expected.getBytes(StandardCharsets.US_ASCII);
        if (a.length != b.length) return null;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        if (diff != 0) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            Map<?, ?> data = MAPPER.readValue(decoded, Map.class);
            Object expObj = data.get("exp");
            if (!(expObj instanceof Number)) return null;
            long exp = ((Number) expObj).longValue();
            if (exp < System.currentTimeMillis()) return null;
            Map<String, Object> result = new HashMap<>();
            result.put("uid", data.get("uid"));
            result.put("username", data.get("username"));
            result.put("exp", exp);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64url(byte[] buf) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
