package com.sylvia.bookhouse.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 火山 Ark 生图（复刻 arkImageGenerate/downloadImage/coverRateCheck/inflightDedup）。
 */
@Component
public class ArkClient {

    private static final Logger log = LoggerFactory.getLogger(ArkClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOST = "ark.cn-beijing.volces.com";
    private static final String PATH = "/api/v3/images/generations";
    private static final String MODEL = "doubao-seedream-5-0-260128";
    private static final long RATE_WINDOW_MS = 60_000L;
    private static final int RATE_LIMIT = 12;

    private final Environment env;
    /** 同 key 在途去重（cover:/scene:） */
    private final Map<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();
    /** 署名限流：uid -> [start, count] */
    private final Map<Long, long[]> rateMap = new ConcurrentHashMap<>();

    public ArkClient(Environment env) {
        this.env = env;
    }

    public boolean hasKey() {
        String k = env.getProperty("ARK_API_KEY");
        return k != null && !k.isEmpty();
    }

    /** 同一署名 60 秒内最多 12 次；返回 false 表示被限流 */
    public synchronized boolean coverRateCheck(long uid) {
        long now = System.currentTimeMillis();
        long[] rec = rateMap.get(uid);
        if (rec == null || now - rec[0] >= RATE_WINDOW_MS) {
            rateMap.put(uid, new long[]{now, 1});
            return true;
        }
        if (rec[1] >= RATE_LIMIT) return false;
        rec[1] += 1;
        return true;
    }

    /** 在途去重：同 key 正在画时，后来的请求等同一个结果 */
    @SuppressWarnings("unchecked")
    public <T> T inflightDedup(String key, java.util.function.Supplier<T> make) {
        CompletableFuture<?> existing = inflight.get(key);
        if (existing != null) {
            try {
                return (T) existing.join();
            } catch (Exception e) {
                // 前序失败，允许重画
            }
        }
        CompletableFuture<T> p = CompletableFuture.supplyAsync(make)
                .whenComplete((r, ex) -> inflight.remove(key));
        CompletableFuture<?> prev = inflight.putIfAbsent(key, p);
        if (prev != null) {
            try {
                return (T) prev.join();
            } catch (Exception e) {
                return p.join();
            }
        }
        return p.join();
    }

    /** 生图，返回公网图片 URL */
    public String generate(String prompt, String size, List<String> images) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL);
        body.put("prompt", prompt);
        body.put("response_format", "url");
        body.put("stream", false);
        body.put("watermark", false);
        body.put("size", size == null || size.isEmpty() ? "1728x2304" : size);
        if (images != null && !images.isEmpty()) body.put("image", images.subList(0, 1));
        String payload = MAPPER.writeValueAsString(body);

        Map<String, String> headers = Map.of("Authorization", "Bearer " + env.getProperty("ARK_API_KEY", ""));
        HttpResponse<String> resp = SimpleHttp.postJson("https://" + HOST + PATH, headers, payload, 120);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("upstream " + resp.statusCode());
        }
        Map<String, Object> j = MAPPER.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> data = (List<Map<String, Object>>) j.get("data");
        if (data == null || data.isEmpty()) throw new RuntimeException("no url");
        Object url = data.get(0).get("url");
        if (!(url instanceof String) || ((String) url).isEmpty()) throw new RuntimeException("no url");
        return (String) url;
    }

    /** 下载图片；跟随重定向；超 2.5MB 拒绝 */
    public DownloadResult download(String url) throws Exception {
        HttpResponse<InputStream> resp = SimpleHttp.getStream(url, 40);
        if (resp.statusCode() != 200) throw new RuntimeException("download " + resp.statusCode());
        String ct = resp.headers().firstValue("content-type").orElse("image/jpeg");
        try (InputStream in = resp.body()) {
            byte[] bytes = in.readNBytes(2_500_000 + 1);
            if (bytes.length > 2_500_000) throw new RuntimeException("too big");
            return new DownloadResult(bytes, ct);
        }
    }

    public static class DownloadResult {
        public final byte[] bytes;
        public final String contentType;

        public DownloadResult(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }

        /** 与 Node 一致：png 才当 png，其余一律 jpeg */
        public String mime() {
            return contentType != null && contentType.toLowerCase().contains("png") ? "image/png" : "image/jpeg";
        }
    }
}
