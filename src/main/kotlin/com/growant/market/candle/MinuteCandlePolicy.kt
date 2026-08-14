package com.growant.market.candle

import java.time.Duration
import java.time.ZoneId

class MinuteCandlePolicy(
    properties: CandleProperties,
) {
    val source: String = properties.source
    val trackedTickers: List<String> = properties.trackedTickers.toList()
    val finalizationDelay: Duration = properties.finalizationDelay
    val schedulerInterval: Duration = properties.schedulerInterval
    val maxQueryRange: Duration = properties.maxQueryRange
    val zoneId: ZoneId = properties.zoneId

    fun tracks(ticker: String): Boolean = ticker in trackedTickers
}
