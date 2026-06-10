package com.growant.portfolio.dto

data class HoldingDto(
    val ticker: String,
    val name: String,
    val qty: Int,
    val avgPrice: Int,
    val currentPrice: Int,
)

data class PortfolioDto(
    val returnRate: Double, // 소수 1자리 반올림 (표시값과 일치)
    val profit: Long,
    val cost: Long,
    val value: Long,
    val holdings: List<HoldingDto>,
)
