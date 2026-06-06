package com.growant.common.error

import com.growant.common.web.ApiError
import com.growant.common.web.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(ex: BusinessException): ResponseEntity<ApiResponse<Nothing>> {
        val c = ex.code
        val body = ApiResponse<Nothing>(
            success = false,
            error = ApiError(
                code = c.name,
                errorCode = c.errorCode,
                eventType = c.eventType,
                message = ex.message,
                retryable = c.retryable,
                traceId = UUID.randomUUID().toString(),
            ),
        )
        return ResponseEntity.status(c.status).body(body)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        val c = ErrorCode.INTERNAL_SERVER_ERROR
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
        return ResponseEntity.status(c.status).body(body)
    }
}
