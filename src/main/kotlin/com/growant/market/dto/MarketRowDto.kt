package com.growant.market.dto

data class MarketRowDto(
    val ticker: String,
    val name: String,
    val price: Int,
    val changeRate: Double,
)
