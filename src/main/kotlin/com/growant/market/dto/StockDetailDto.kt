package com.growant.market.dto

data class StockDetailDto(
    val ticker: String,
    val name: String,
    val price: Int,
    val changeRate: Double,
    val candles: List<Int>, // [0]=최근 ... [9]=오래된 (프론트 _MiniChart가 reversed 처리)
    val high52w: Int,
    val low52w: Int,
    val volume: Long,
    val marketCapEok: Long,
    val per: Double,
    val pbr: Double,
)
