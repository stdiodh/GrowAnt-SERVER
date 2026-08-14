package com.growant.market.candle

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("market.candles")
data class CandleProperties(
    val source: String = "sim",
    val collectionEnabled: Boolean = false,
    val trackedTickers: List<String> = DEFAULT_TRACKED_TICKERS,
    val finalizationDelay: Duration = Duration.ofSeconds(5),
    val schedulerInterval: Duration = Duration.ofSeconds(1),
    val maxQueryRange: Duration = Duration.ofDays(7),
    val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
        require(source.length <= SOURCE_MAX_LENGTH) { "source must not exceed $SOURCE_MAX_LENGTH characters" }
        require(trackedTickers.isNotEmpty()) { "trackedTickers must not be empty" }
        require(trackedTickers.all(TICKER_PATTERN::matches)) { "trackedTickers must contain six-digit tickers" }
        require(trackedTickers.distinct().size == trackedTickers.size) { "trackedTickers must not contain duplicates" }
        require(!finalizationDelay.isNegative) { "finalizationDelay must not be negative" }
        require(!schedulerInterval.isZero && !schedulerInterval.isNegative) { "schedulerInterval must be positive" }
        require(!maxQueryRange.isZero && !maxQueryRange.isNegative) { "maxQueryRange must be positive" }
    }

    companion object {
        val DEFAULT_TRACKED_TICKERS = listOf(
            "005930",
            "000660",
            "035720",
            "035420",
            "005380",
        )

        private val TICKER_PATTERN = Regex("[0-9]{6}")
        private const val SOURCE_MAX_LENGTH = 40
    }
}
