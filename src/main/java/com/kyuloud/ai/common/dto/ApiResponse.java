package com.kyuloud.ai.common.dto;

import java.time.Instant;

/**
 * 모든 API의 공통 응답 래퍼.
 *
 * @param success 요청 성공 여부
 * @param data    성공 시 페이로드 (실패 시 null)
 * @param error   실패 시 에러 정보 (성공 시 null)
 * @param timestamp 응답 생성 시각
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorDetail error,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    public static <T> ApiResponse<T> fail(ErrorDetail error) {
        return new ApiResponse<>(false, null, error, Instant.now());
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return fail(new ErrorDetail(code, message));
    }

    public record ErrorDetail(String code, String message) {
    }
}
