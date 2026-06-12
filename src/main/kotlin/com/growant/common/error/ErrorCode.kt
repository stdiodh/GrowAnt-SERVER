package com.growant.common.error

import org.springframework.http.HttpStatus

/**
 * 13주차 에러 명세의 핵심 코드 시드. 도메인 개발 시 확장한다.
 * (eventType + errorCode 병용, JSON 응답)
 */
enum class ErrorCode(
    val errorCode: Int,
    val status: HttpStatus,
    val eventType: String,
    val retryable: Boolean,
    val defaultMessage: String,
) {
    // SYSTEM
    INTERNAL_SERVER_ERROR(1000, HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", true, "서버 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(1001, HttpStatus.SERVICE_UNAVAILABLE, "SYSTEM_ERROR", true, "잠시 후 다시 시도해 주세요."),

    // AUTH
    UNAUTHENTICATED(2000, HttpStatus.UNAUTHORIZED, "AUTH_ERROR", false, "로그인이 필요합니다."),
    TOKEN_EXPIRED(2001, HttpStatus.UNAUTHORIZED, "AUTH_ERROR", true, "토큰이 만료되었습니다."),
    AGE_RESTRICTED(2002, HttpStatus.FORBIDDEN, "AUTH_ERROR", false, "이용할 수 없는 연령입니다."),

    // VALIDATION / MARKET
    INVALID_TICKER(3000, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", false, "존재하지 않는 종목입니다."),
    INVALID_ORDER(3001, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", false, "잘못된 주문입니다."),
    INVALID_LOGIN(3002, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", false, "잘못된 로그인 요청입니다."),
    MARKET_DATA_UNAVAILABLE(3500, HttpStatus.SERVICE_UNAVAILABLE, "MARKET_ERROR", true, "시세를 불러오지 못했습니다."),

    // ORDER (가상 거래)
    ORDER_INSUFFICIENT_FUNDS(4000, HttpStatus.CONFLICT, "ORDER_ERROR", false, "잔고가 부족합니다."),
    ORDER_MARKET_CLOSED(4001, HttpStatus.CONFLICT, "ORDER_ERROR", false, "장 운영 시간이 아닙니다."),
    ORDER_INSUFFICIENT_HOLDINGS(4002, HttpStatus.CONFLICT, "ORDER_ERROR", false, "보유 수량이 부족합니다."),

    // AI
    AI_RATE_LIMITED(6000, HttpStatus.TOO_MANY_REQUESTS, "AI_ERROR", true, "요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    AI_DAILY_LIMIT_EXCEEDED(6001, HttpStatus.TOO_MANY_REQUESTS, "AI_ERROR", false, "오늘 사용량을 모두 소진했습니다."),
    AI_AD_REQUIRED(6002, HttpStatus.FORBIDDEN, "AI_ERROR", false, "광고 시청 후 결과를 확인할 수 있습니다."),

    // PAYMENT (mock)
    PAYMENT_FAILED(7000, HttpStatus.PAYMENT_REQUIRED, "PAYMENT_ERROR", false, "결제에 실패했습니다."),
    ;
}

/** 비즈니스 예외 — GlobalExceptionHandler가 ApiError로 변환한다. */
class BusinessException(
    val code: ErrorCode,
    override val message: String = code.defaultMessage,
) : RuntimeException(message)
