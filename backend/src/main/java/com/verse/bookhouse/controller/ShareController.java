package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 荐书笺：POST /api/share（登录寄存）+ GET /api/share/:code（匿名取书，books fallback）。
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> put(@RequestBody(required = false) Map<String, Object> body) {
        String code = body == null ? "" : String.valueOf(body.get("code") == null ? "" : body.get("code"));
        String payload = body == null || body.get("payload") == null ? "" : String.valueOf(body.get("payload"));
        return ResponseEntity.ok(shareService.putShare(code, payload));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String code, HttpServletRequest req) {
        return ResponseEntity.ok(shareService.getShare(code, req));
    }
}
