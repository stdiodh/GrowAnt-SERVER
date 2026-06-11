package com.growant.common.config

import com.growant.common.error.ErrorCode
import com.growant.common.web.ApiError
import com.growant.common.web.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** 보호 API의 401을 ApiResponse 에러 envelope로 통일 — 토큰 부재·만료·서명 오류 동일 취급(스펙 §3.4). */
@Component
class ApiAuthEntryPoint(private val objectMapper: ObjectMapper) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val c = ErrorCode.UNAUTHENTICATED
        response.status = c.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        val body = ApiResponse<Nothing>(
            success = false,
            error = ApiError(
                code = c.name,
                errorCode = c.errorCode,
                eventType = c.eventType,
                message = c.defaultMessage,
                retryable = c.retryable,
                traceId = UUID.randomUUID().toString(),
            ),
        )
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}
