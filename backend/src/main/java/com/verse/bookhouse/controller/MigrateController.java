package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.security.UserContext;
import com.sylvia.bookhouse.service.MigrateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v3.5 跨端迁移：POST /api/migrate/preview（dry-run）+ POST /api/migrate/import（登录）。
 */
@RestController
@RequestMapping("/api/migrate")
public class MigrateController {

    private final MigrateService migrateService;

    public MigrateController(MigrateService migrateService) {
        this.migrateService = migrateService;
    }

    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(migrateService.migrate(UserContext.uid(), body, true));
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importSave(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(migrateService.migrate(UserContext.uid(), body, false));
    }
}
