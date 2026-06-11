package com.growant.trading.dto

data class OrderRequestDto(
    val ticker: String,
    val isBuy: Boolean,
    val qty: Int,
)
