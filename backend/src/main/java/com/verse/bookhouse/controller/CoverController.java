package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.security.UserContext;
import com.sylvia.bookhouse.service.CoverService;
import com.sylvia.bookhouse.service.SceneService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 书屋画师：POST /api/cover/generate（书封）+ POST /api/cover/scene（五档插图，登录）。
 */
@RestController
@RequestMapping("/api/cover")
public class CoverController {

    private final CoverService coverService;
    private final SceneService sceneService;

    public CoverController(CoverService coverService, SceneService sceneService) {
        this.coverService = coverService;
        this.sceneService = sceneService;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest req) {
        return ResponseEntity.ok(coverService.generate(body, UserContext.uid(), req));
    }

    @PostMapping("/scene")
    public ResponseEntity<Map<String, Object>> scene(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(sceneService.generateScene(body, UserContext.uid()));
    }
}
