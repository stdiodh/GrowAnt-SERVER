package com.growant.market.candle

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.market.candle.port.MinuteCandleRepository
import com.growant.market.port.InstrumentCatalog
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class MinuteCandleQueryService(
    private val instrumentCatalog: InstrumentCatalog,
    private val repository: MinuteCandleRepository,
    private val policy: MinuteCandlePolicy = MinuteCandlePolicy(CandleProperties()),
) {
    @Transactional(readOnly = true)
    fun getCandles(ticker: String, fromInclusive: Instant, toExclusive: Instant): MinuteCandleSeries {
        validateTicker(ticker)
        validateRange(fromInclusive, toExclusive)

        return MinuteCandleSeries(
            ticker = ticker,
            zoneId = policy.zoneId,
            candles = repository.find(ticker, fromInclusive, toExclusive),
        )
    }

    private fun validateTicker(ticker: String) {
        if (!instrumentCatalog.contains(ticker)) {
            throw BusinessException(ErrorCode.INVALID_TICKER)
        }
        if (!policy.tracks(ticker)) {
            throw BusinessException(ErrorCode.CANDLE_TICKER_NOT_TRACKED)
        }
    }

    private fun validateRange(fromInclusive: Instant, toExclusive: Instant) {
        if (!fromInclusive.isBefore(toExclusive) ||
            java.time.Duration.between(fromInclusive, toExclusive) > policy.maxQueryRange
        ) {
            throw BusinessException(ErrorCode.INVALID_CANDLE_RANGE)
        }
    }
}
