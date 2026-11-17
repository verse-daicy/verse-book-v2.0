package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.security.UserContext;
import com.sylvia.bookhouse.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 注册/登录/当前用户（复刻 routes/auth.js，路径 /api/auth/*）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) Map<String, Object> body) {
        String username = str(body, "username");
        String password = str(body, "password");
        // 增加校验
        if (username == null || username.trim().length() < 2 || username.length() > 20) {
            throw new ApiException(400, "用户名长度必须在 2-20 个字符之间");
        }
        if (password == null || password.length() < 6) {
            throw new ApiException(400, "密码长度不能少于 6 位");
        }
        Map<String, Object> res = authService.register(username, password);
        return build(res);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> res = authService.login(str(body, "username"), str(body, "password"));
        return build(res);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        Map<String, Object> res = new HashMap<>();
        res.put("username", UserContext.username());
        return ResponseEntity.ok(res);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static ResponseEntity<Map<String, Object>> build(Map<String, Object> res) {
        int code = res.containsKey("code") ? ((Number) res.remove("code")).intValue() : 200;
        if (code >= 400) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", res.getOrDefault("error", "error"));
            return ResponseEntity.status(code).body(err);
        }
        return ResponseEntity.status(code).body(res);
    }
}
