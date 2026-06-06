package com.growant.common.web

import java.time.OffsetDateTime

/** 공통 응답 래퍼. 성공: {success:true, data}, 실패: {success:false, error} (13주차 표준). */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)
    }
}

data class ApiError(
    val code: String,        // 서버 enum 상수명과 1:1
    val errorCode: Int,      // 숫자 코드 (로깅·검색용)
    val eventType: String,   // FE 1차 분기
    val message: String,     // 사용자 노출 메시지
    val retryable: Boolean,  // 재시도 화면 노출 여부
    val timestamp: OffsetDateTime = OffsetDateTime.now(),
    val traceId: String? = null,
)
