package com.growant.market.toss

import com.growant.market.provider.ProviderOhlcv
import com.growant.market.provider.hasSameNumericValues
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val DOMESTIC_TICKER = Regex("^[0-9]{6}$")

@JvmInline
value class TossCandleCursor private constructor(val rawValue: String) {
    val timestamp: OffsetDateTime
        get() = OffsetDateTime.parse(rawValue)

    companion object {
        fun parse(rawValue: String): TossCandleCursor {
            require(rawValue.isNotBlank()) { "Toss candle cursor must not be blank" }
            try {
                OffsetDateTime.parse(rawValue)
            } catch (exception: DateTimeParseException) {
                throw IllegalArgumentException("Toss candle cursor must be an ISO-8601 offset date-time", exception)
            }
            return TossCandleCursor(rawValue)
        }

        fun from(timestamp: OffsetDateTime): TossCandleCursor =
            TossCandleCursor(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(timestamp))
    }
}

/**
 * GrowAnt의 국내 6자리 종목 1분봉 비교 요청 계약.
 *
 * 토스의 기본값은 adjusted=true이므로 원시 가격 비교에서는 false를 반드시 명시한다.
 * 토스 응답에는 venue가 없으므로 이 타입만으로 KRX-only 데이터라고 판단할 수 없다.
 */
data class TossDomesticMinuteCandleRequest(
    val symbol: String,
    val count: Int = 200,
    val before: TossCandleCursor? = null,
) {
    init {
        require(DOMESTIC_TICKER.matches(symbol)) { "symbol must be a 6-digit domestic ticker" }
        require(count in 1..200) { "count must be between 1 and 200" }
    }

    fun queryParameters(): Map<String, String> = buildMap {
        put("symbol", symbol)
        put("interval", "1m")
        put("count", count.toString())
        before?.let { put("before", it.rawValue) }
        put("adjusted", "false")
    }
}

data class TossMinuteCandle(
    val timestamp: OffsetDateTime,
    val values: ProviderOhlcv,
    val currency: String,
)

data class TossMinuteCandlePage(
    val symbol: String,
    val candles: List<TossMinuteCandle>,
    val nextBefore: TossCandleCursor?,
)

class TossCandleResponseParser(
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    fun parse(
        request: TossDomesticMinuteCandleRequest,
        responseBody: String,
    ): TossMinuteCandlePage {
        require(responseBody.isNotBlank()) { "Toss candle response must not be blank" }

        val root = try {
            objectMapper.readTree(responseBody)
        } catch (exception: JacksonException) {
            throw IllegalArgumentException("Toss candle response is not valid JSON", exception)
        }
        require(root.isObject) { "Toss candle response root must be an object" }

        val result = root.path("result")
        require(result.isObject) { "Toss candle response result must be an object" }
        val candleNodes = result.path("candles")
        require(candleNodes.isArray) { "Toss candle response candles must be an array" }

        val candles = candleNodes.mapIndexed { index, node -> parseCandle(node, index) }
        require(candles.size <= request.count) { "Toss candle response exceeds the requested count" }
        request.before?.let { cursor ->
            require(candles.none { it.timestamp.toInstant().isAfter(cursor.timestamp.toInstant()) }) {
                "Toss candle response contains a timestamp after the inclusive before cursor"
            }
        }
        val nextBeforeNode = result.get("nextBefore")
        val nextBefore = when {
            nextBeforeNode == null || nextBeforeNode.isNull -> null
            nextBeforeNode.isString -> TossCandleCursor.parse(nextBeforeNode.asString())
            else -> throw IllegalArgumentException("Toss candle response nextBefore must be a string or null")
        }

        return TossMinuteCandlePage(
            symbol = request.symbol,
            candles = candles,
            nextBefore = nextBefore,
        )
    }

    private fun parseCandle(node: JsonNode, index: Int): TossMinuteCandle {
        require(node.isObject) { "Toss candle[$index] must be an object" }

        return TossMinuteCandle(
            timestamp = parseTimestamp(requiredText(node, "timestamp", index), "candle[$index].timestamp"),
            values = ProviderOhlcv(
                openPrice = requiredDecimal(node, "openPrice", index),
                highPrice = requiredDecimal(node, "highPrice", index),
                lowPrice = requiredDecimal(node, "lowPrice", index),
                closePrice = requiredDecimal(node, "closePrice", index),
                volume = requiredDecimal(node, "volume", index),
            ),
            currency = requiredText(node, "currency", index),
        )
    }

    private fun requiredDecimal(node: JsonNode, field: String, index: Int): BigDecimal {
        val value = requiredText(node, field, index)
        require(value.length <= 30) { "Toss candle[$index].$field must not exceed 30 characters" }
        return value.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Toss candle[$index].$field must be a decimal string")
    }

    private fun requiredText(node: JsonNode, field: String, index: Int): String {
        val value = node.get(field)
        require(value != null && value.isString) { "Toss candle[$index].$field must be a string" }
        return value.asString().also {
            require(it.isNotBlank()) { "Toss candle[$index].$field must not be blank" }
        }
    }

    private fun parseTimestamp(value: String, field: String): OffsetDateTime = try {
        OffsetDateTime.parse(value)
    } catch (exception: DateTimeParseException) {
        throw IllegalArgumentException("Toss $field must be an ISO-8601 offset date-time", exception)
    }
}

object TossCandlePageMerger {
    fun merge(pages: List<TossMinuteCandlePage>): List<TossMinuteCandle> {
        if (pages.isEmpty()) return emptyList()

        val symbol = pages.first().symbol
        require(pages.all { it.symbol == symbol }) { "Toss candle pages must have the same symbol" }

        val candlesByTimestamp = linkedMapOf<java.time.Instant, TossMinuteCandle>()
        pages.forEach { page ->
            page.candles.forEach { candle ->
                val key = candle.timestamp.toInstant()
                val existing = candlesByTimestamp[key]
                if (existing == null) {
                    candlesByTimestamp[key] = candle
                } else {
                    require(existing.hasSameValues(candle)) {
                        "Toss candle pages contain conflicting values at ${candle.timestamp}"
                    }
                }
            }
        }

        return candlesByTimestamp.values.sortedByDescending { it.timestamp.toInstant() }
    }

    private fun TossMinuteCandle.hasSameValues(other: TossMinuteCandle): Boolean =
        timestamp.toInstant() == other.timestamp.toInstant() &&
            currency == other.currency &&
            values.hasSameNumericValues(other.values)
}

object TossCandlePagination {
    /** null은 마지막 페이지다. 동일하거나 미래 방향 cursor는 순환으로 간주한다. */
    fun nextBefore(
        currentBefore: TossCandleCursor?,
        page: TossMinuteCandlePage,
    ): TossCandleCursor? {
        val next = page.nextBefore ?: return null
        require(currentBefore == null || next.timestamp.toInstant().isBefore(currentBefore.timestamp.toInstant())) {
            "Toss nextBefore must move to an earlier instant"
        }
        return next
    }
}
