package com.growant.market.candle

import java.time.Instant

data class TradeTick(
    val ticker: String,
    val price: Int,
    val quantity: Long,
    val occurredAt: Instant,
    val sequence: Long,
)

data class MinuteCandle(
    val ticker: String,
    val bucketStart: Instant,
    val open: Int,
    val high: Int,
    val low: Int,
    val close: Int,
    val volume: Long,
    val tradeCount: Long,
    val revision: Int = 0,
    val isFinal: Boolean,
    val source: String,
)
