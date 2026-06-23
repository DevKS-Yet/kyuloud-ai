package com.kyuloud.ai.common.controller;

import com.kyuloud.ai.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Phase 0 동작 확인용 핑 엔드포인트.
 * 앱 부팅 및 웹 계층(REST + 공통 응답 래퍼)이 정상인지 확인한다.
 */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping() {
        return ApiResponse.ok(Map.of("message", "pong"));
    }
}
