package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.security.UserContext;
import com.sylvia.bookhouse.service.ChatService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 大模型流式对话：POST /api/chat（登录，SSE 透传）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public void chat(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws Exception {
        try {
            Object messages = body == null ? null : body.get("messages");
            // 简单校验，防止恶意传入空消息或者过长消息
            if (messages == null) {
                response.sendError(400, "消息不能为空");
                return;
            }
            chatService.chat(messages, UserContext.uid(), response);
        } catch (Exception e) {
            // 如果 SSE 已经开始输出，这里只能记录日志；如果没输出，可以返回 500
            log.error("Chat API 发生异常: {}", e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(500, "AI 服务响应异常，请稍后再试");
            }
        }
    }
}
