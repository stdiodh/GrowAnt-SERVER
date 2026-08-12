package com.growant.market.candle.port

import com.growant.market.candle.MinuteCandle
import java.time.Instant

interface MinuteCandleRepository {
    fun save(candle: MinuteCandle): MinuteCandleSaveResult

    fun upsert(candle: MinuteCandle): Int = when (save(candle)) {
        MinuteCandleSaveResult.INSERTED_OR_UPDATED -> 1
        MinuteCandleSaveResult.UNCHANGED,
        MinuteCandleSaveResult.STALE_REVISION,
        MinuteCandleSaveResult.REVISION_CONFLICT,
        -> 0
    }

    fun find(
        ticker: String,
        fromInclusive: Instant,
        toExclusive: Instant,
    ): List<MinuteCandle>
}

enum class MinuteCandleSaveResult {
    INSERTED_OR_UPDATED,
    UNCHANGED,
    STALE_REVISION,
    REVISION_CONFLICT,
}
