package com.growant.trading.dto

/** 프론트 Trade 모델과 1:1 미러 — time은 "MM.dd HH:mm" 문자열. */
data class TradeDto(
    val name: String,
    val isBuy: Boolean,
    val price: Int,
    val qty: Int,
    val amount: Long,
    val time: String,
)
