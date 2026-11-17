package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.service.SceneService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 画师留底：GET /api/scenes/art/:bookKey/:mood（匿名取图）+ POST /api/scenes/art（登录裱画）。
 */
@RestController
@RequestMapping("/api/scenes/art")
public class SceneArtController {

    private final SceneService sceneService;

    public SceneArtController(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    @GetMapping("/{bookKey}/{mood}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String bookKey, @PathVariable String mood) {
        return ResponseEntity.ok(sceneService.getSceneArt(bookKey, mood));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> put(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(sceneService.putSceneArt(body));
    }
}
