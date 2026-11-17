package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.service.TtsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 书屋说书人：POST /api/tts（登录，返回 audio/mpeg）。
 */
@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping
    public void tts(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws Exception {
        ttsService.handleTts(body, response);
    }
}
