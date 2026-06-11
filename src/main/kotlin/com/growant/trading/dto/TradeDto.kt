package com.growant.trading.dto

import com.fasterxml.jackson.annotation.JsonProperty

/** 프론트 Trade 모델과 1:1 미러 — time은 "MM.dd HH:mm" 문자열. */
data class TradeDto(
    val name: String,
    // jackson-kotlin이 Boolean getter의 is 접두사를 벗겨 "buy"로 직렬화함 — getter 타깃(@get:)으로 키를 고정. 제거 금지.
    @get:JsonProperty("isBuy") val isBuy: Boolean,
    val price: Int,
    val qty: Int,
    val amount: Long,
    val time: String,
)
