package com.growant.market.candle

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.market.MarketService
import com.growant.market.candle.dto.MinuteCandleDto
import com.growant.market.candle.dto.MinuteCandleSeriesDto
import com.growant.market.candle.persistence.MinuteCandleStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

@Service
class MinuteCandleQueryService(
    private val marketService: MarketService,
    private val store: MinuteCandleStore,
) {
    @Transactional(readOnly = true)
    fun getCandles(ticker: String, fromInclusive: Instant, toExclusive: Instant): MinuteCandleSeriesDto {
        validateTicker(ticker)
        validateRange(fromInclusive, toExclusive)

        return MinuteCandleSeriesDto(
            ticker = ticker,
            candles = store.find(ticker, fromInclusive, toExclusive).map { candle ->
                MinuteCandleDto(
                    time = candle.bucketStart.atZone(SEOUL).toOffsetDateTime(),
                    open = candle.open,
                    high = candle.high,
                    low = candle.low,
                    close = candle.close,
                    volume = candle.volume,
                    tradeCount = candle.tradeCount,
                    final = candle.isFinal,
                    revision = candle.revision,
                )
            },
        )
    }

    private fun validateTicker(ticker: String) {
        if (marketService.getMarket().none { it.ticker == ticker }) {
            throw BusinessException(ErrorCode.INVALID_TICKER)
        }
    }

    private fun validateRange(fromInclusive: Instant, toExclusive: Instant) {
        if (!fromInclusive.isBefore(toExclusive) || Duration.between(fromInclusive, toExclusive) > MAX_RANGE) {
            throw BusinessException(ErrorCode.INVALID_CANDLE_RANGE)
        }
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val MAX_RANGE: Duration = Duration.ofDays(7)
    }
}
