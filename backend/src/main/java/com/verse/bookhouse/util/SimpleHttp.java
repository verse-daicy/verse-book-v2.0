package com.sylvia.bookhouse.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 轻量 HTTP 客户端（JDK 内置 HttpClient 封装）。
 */
public final class SimpleHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SimpleHttp() {
    }

    public static HttpResponse<String> postJson(String url, Map<String, String> headers, String jsonBody,
                                                int timeoutSec) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (headers != null) headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 流式 POST：返回输入流（SSE 透传用），调用方负责读取与关闭 */
    public static HttpResponse<InputStream> postJsonStream(String url, Map<String, String> headers, String jsonBody,
                                                           int timeoutSec) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (headers != null) headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    public static HttpResponse<InputStream> getStream(String url, int timeoutSec) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .GET();
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    }
}
