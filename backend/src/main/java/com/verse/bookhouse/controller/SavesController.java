package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.exception.ApiException;
import com.sylvia.bookhouse.security.UserContext;
import com.sylvia.bookhouse.service.SaveService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 云端存档：GET /api/saves + PUT /api/saves（登录）。
 */
@RestController
@RequestMapping("/api/saves")
public class SavesController {

    private final SaveService saveService;

    public SavesController(SaveService saveService) {
        this.saveService = saveService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.ok(saveService.getSaves(UserContext.uid()));
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
}
