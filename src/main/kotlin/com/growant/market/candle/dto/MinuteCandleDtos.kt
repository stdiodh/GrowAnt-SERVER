package com.growant.market.candle.dto

import java.time.OffsetDateTime

data class MinuteCandleDto(
    val time: OffsetDateTime,
    val open: Int,
    val high: Int,
    val low: Int,
    val close: Int,
    val volume: Long,
    val tradeCount: Long,
    val final: Boolean,
    val revision: Int,
)

data class MinuteCandleSeriesDto(
    val ticker: String,
    val interval: String = "1m",
    val timezone: String = "Asia/Seoul",
    val candles: List<MinuteCandleDto>,
)
