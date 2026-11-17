package com.sylvia.bookhouse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sylvia.bookhouse.config.LlmConfig;
import com.sylvia.bookhouse.exception.ApiException;
import com.sylvia.bookhouse.mapper.LlmCallMapper;
import com.sylvia.bookhouse.model.LlmCall;
import com.sylvia.bookhouse.util.SimpleHttp;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型流式转发（复刻 handleChat）：上游返回什么 SSE 流，原样转给浏览器；
 * 客户端中断静默结束，结束前落一条 llm_calls 留痕。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmConfig llmConfig;
    private final LlmCallMapper llmCallMapper;

    public ChatService(LlmConfig llmConfig, LlmCallMapper llmCallMapper) {
        this.llmConfig = llmConfig;
        this.llmCallMapper = llmCallMapper;
    }

    public void chat(Object messagesObj, long userId, HttpServletResponse response) throws Exception {
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            throw new ApiException(400, "没有要翻开的书页");
        }
        String useModel = llmConfig.model();
        String body;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", useModel);
            payload.put("stream", true);
            payload.put("temperature", 0.85);
            payload.put("do_sample", true);
            payload.put("messages", messagesObj);
            payload.put("max_tokens", 1024);
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ApiException(400, "没有要翻开的书页");
        }

        String url = llmConfig.baseUrl() + "/chat/completions";
        Map<String, String> headers = Map.of("Authorization", "Bearer " + llmConfig.apiKey());
        HttpResponse<InputStream> upstream;
        try {
            upstream = SimpleHttp.postJsonStream(url, headers, body, 60);
        } catch (Exception e) {
            log.warn("[LLM] upstream fetch error", e);
            throw new ApiException(502, "书页没能翻开（书屋连不上书架深处），请稍后再试");
        }

        if (upstream.statusCode() != 200) {
            String text = "";
            try (InputStream in = upstream.body()) {
                text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            log.warn("[LLM] upstream HTTP {} {}", upstream.statusCode(), text.length() > 300 ? text.substring(0, 300) : text);
            if (upstream.statusCode() == 401 || upstream.statusCode() == 403) {
                throw new ApiException(502, "书屋的钥匙暂时失效了，请联系管理员");
            }
            if (upstream.statusCode() == 429) {
                throw new ApiException(429, "书屋前访客有点多，稍等片刻再翻页");
            }
            throw new ApiException(502, "书页被风暂时合上了（上游 " + upstream.statusCode() + "）");
        }

        response.setStatus(200);
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Access-Control-Allow-Origin", "*");
        boolean logged = false;
        try (InputStream in = upstream.body(); OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception e) {
            // 客户端中断等，静默结束
        } finally {
            if (!logged) {
                try {
                    LlmCall call = new LlmCall();
                    call.setUserId(userId);
                    call.setModel(useModel);
                    call.setCreatedAt(System.currentTimeMillis());
                    llmCallMapper.insert(call);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
