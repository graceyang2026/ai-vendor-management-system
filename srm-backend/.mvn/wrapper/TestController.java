/Users/grace/Desktop/PIP2026/qoder/ai-vendor-management-system/srm-backend/src/main/java/com/srm/core/controller/TestController.java
package com.srm.core.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "code", 200,
                "message", "pong",
                "timestamp", LocalDateTime.now().toString()
        );
    }
}
