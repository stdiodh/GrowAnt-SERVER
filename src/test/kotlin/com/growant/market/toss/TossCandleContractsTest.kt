package com.growant.market.toss

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime

class TossCandleContractsTest {
    private val parser = TossCandleResponseParser()

    @Test
    fun `request always uses raw one-minute candles`() {
        val request = TossDomesticMinuteCandleRequest(
            symbol = "005930",
            count = 200,
            before = TossCandleCursor.from(OffsetDateTime.parse("2026-08-16T09:30:00+09:00")),
        )

        assertThat(request.queryParameters()).containsExactlyEntriesOf(
            linkedMapOf(
                "symbol" to "005930",
                "interval" to "1m",
                "count" to "200",
                "before" to "2026-08-16T09:30:00+09:00",
                "adjusted" to "false",
            ),
        )
    }

    @Test
    fun `request rejects a non-domestic ticker and an out-of-range count`() {
        assertThat(TossDomesticMinuteCandleRequest(symbol = "005930", count = 1).count).isEqualTo(1)
        assertThat(TossDomesticMinuteCandleRequest(symbol = "005930", count = 200).count).isEqualTo(200)

        assertThatThrownBy { TossDomesticMinuteCandleRequest(symbol = "AAPL") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("symbol must be a 6-digit domestic ticker")

        listOf(0, 201).forEach { invalidCount ->
            assertThatThrownBy { TossDomesticMinuteCandleRequest(symbol = "005930", count = invalidCount) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("count must be between 1 and 200")
        }
    }

    @Test
    fun `parses every documented candle field without inventing finality`() {
        val page = parser.parse(
            request = TossDomesticMinuteCandleRequest("005930"),
            responseBody = response(
                candles = candle(
                    timestamp = "2026-08-16T09:31:00+09:00",
                    open = "72000.0",
                    high = "72100",
                    low = "71950",
                    close = "72050",
                    volume = "15200.5",
                    currency = "KRW",
                ),
                nextBefore = "\"2026-08-16T09:31:00+09:00\"",
            ),
        )

        assertThat(page.symbol).isEqualTo("005930")
        assertThat(page.nextBefore?.rawValue).isEqualTo("2026-08-16T09:31:00+09:00")
        assertThat(page.nextBefore?.timestamp).isEqualTo(OffsetDateTime.parse("2026-08-16T09:31:00+09:00"))
        assertThat(
            TossDomesticMinuteCandleRequest("005930", before = page.nextBefore).queryParameters()["before"],
        ).isEqualTo("2026-08-16T09:31:00+09:00")
        assertThat(page.candles).hasSize(1)
        val parsed = page.candles.single()
        assertThat(parsed.timestamp).isEqualTo(OffsetDateTime.parse("2026-08-16T09:31:00+09:00"))
        assertThat(parsed.values.openPrice).isEqualByComparingTo(BigDecimal("72000.0"))
        assertThat(parsed.values.highPrice).isEqualByComparingTo(BigDecimal("72100"))
        assertThat(parsed.values.lowPrice).isEqualByComparingTo(BigDecimal("71950"))
        assertThat(parsed.values.closePrice).isEqualByComparingTo(BigDecimal("72050"))
        assertThat(parsed.values.volume).isEqualByComparingTo(BigDecimal("15200.5"))
        assertThat(parsed.currency).isEqualTo("KRW")
    }

    @Test
    fun `accepts an empty last page and a missing nextBefore`() {
        val page = parser.parse(
            request = TossDomesticMinuteCandleRequest("005930"),
            responseBody = """
                {
                  "result": {
                    "candles": []
                  }
                }
            """.trimIndent(),
        )

        assertThat(page.candles).isEmpty()
        assertThat(page.nextBefore).isNull()
    }

    @Test
    fun `preserves an unknown future currency code`() {
        val page = parser.parse(
            request = TossDomesticMinuteCandleRequest("005930"),
            responseBody = response(
                candles = candle(currency = "KRW_NEXT"),
                nextBefore = "null",
            ),
        )

        assertThat(page.candles.single().currency).isEqualTo("KRW_NEXT")
    }

    @Test
    fun `rejects missing or wrongly typed documented fields`() {
        val missingVolume = """
            {
              "result": {
                "candles": [
                  {
                    "timestamp": "2026-08-16T09:31:00+09:00",
                    "openPrice": "72000",
                    "highPrice": "72100",
                    "lowPrice": "71950",
                    "closePrice": "72050",
                    "currency": "KRW"
                  }
                ]
              }
            }
        """.trimIndent()

        assertThatThrownBy { parser.parse(TossDomesticMinuteCandleRequest("005930"), missingVolume) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss candle[0].volume must be a string")

        val numericPrice = response(
            candles = candle(open = "72000").replace("\"openPrice\": \"72000\"", "\"openPrice\": 72000"),
            nextBefore = "null",
        )
        assertThatThrownBy { parser.parse(TossDomesticMinuteCandleRequest("005930"), numericPrice) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss candle[0].openPrice must be a string")
    }

    @Test
    fun `rejects invalid decimal and timestamp strings`() {
        val invalidDecimal = response(candles = candle(volume = "not-a-number"), nextBefore = "null")
        assertThatThrownBy { parser.parse(TossDomesticMinuteCandleRequest("005930"), invalidDecimal) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss candle[0].volume must be a decimal string")

        val invalidTimestamp = response(candles = candle(timestamp = "2026-08-16 09:31:00"), nextBefore = "null")
        assertThatThrownBy { parser.parse(TossDomesticMinuteCandleRequest("005930"), invalidTimestamp) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss candle[0].timestamp must be an ISO-8601 offset date-time")

        val tooLong = response(candles = candle(volume = "1234567890123456789012345678901"), nextBefore = "null")
        assertThatThrownBy { parser.parse(TossDomesticMinuteCandleRequest("005930"), tooLong) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss candle[0].volume must not exceed 30 characters")

        val maximumLength = response(candles = candle(volume = "123456789012345678901234567890"), nextBefore = "null")
        assertThat(
            parser.parse(TossDomesticMinuteCandleRequest("005930"), maximumLength).candles.single().values.volume,
        ).isEqualByComparingTo(BigDecimal("123456789012345678901234567890"))

        val invalidNextBefore = response(candles = candle(), nextBefore = "\"not-a-date\"")
        assertThatThrownBy { parser.parse(TossDomesticMinuteCandleRequest("005930"), invalidNextBefore) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss candle cursor must be an ISO-8601 offset date-time")
    }

    @Test
    fun `rejects a page outside the requested count or inclusive cursor`() {
        val twoCandles = response(
            candles = listOf(
                candle(timestamp = "2026-08-16T09:31:00+09:00"),
                candle(timestamp = "2026-08-16T09:30:00+09:00"),
            ).joinToString(","),
            nextBefore = "null",
        )
        assertThatThrownBy {
            parser.parse(TossDomesticMinuteCandleRequest("005930", count = 1), twoCandles)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss candle response exceeds the requested count")

        assertThat(parser.parse(TossDomesticMinuteCandleRequest("005930", count = 2), twoCandles).candles)
            .hasSize(2)

        val before = TossCandleCursor.parse("2026-08-16T09:30:00+09:00")
        val futureCandle = response(
            candles = candle(timestamp = "2026-08-16T09:31:00+09:00"),
            nextBefore = "null",
        )
        assertThatThrownBy {
            parser.parse(TossDomesticMinuteCandleRequest("005930", before = before), futureCandle)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss candle response contains a timestamp after the inclusive before cursor")
    }

    @Test
    fun `deduplicates the inclusive page boundary and sorts deterministically`() {
        val first = parser.parse(
            TossDomesticMinuteCandleRequest("005930"),
            response(
                candles = listOf(
                    candle(timestamp = "2026-08-16T09:32:00+09:00", close = "72050"),
                    candle(timestamp = "2026-08-16T09:31:00+09:00", close = "72000"),
                ).joinToString(","),
                nextBefore = "\"2026-08-16T09:31:00+09:00\"",
            ),
        )
        val second = parser.parse(
            TossDomesticMinuteCandleRequest("005930", before = first.nextBefore),
            response(
                candles = listOf(
                    candle(timestamp = "2026-08-16T09:30:00+09:00", close = "71900"),
                    candle(timestamp = "2026-08-16T09:31:00+09:00", close = "72000.0"),
                ).joinToString(","),
                nextBefore = "null",
            ),
        )

        assertThat(TossCandlePageMerger.merge(listOf(first, second)).map { it.timestamp })
            .containsExactly(
                OffsetDateTime.parse("2026-08-16T09:32:00+09:00"),
                OffsetDateTime.parse("2026-08-16T09:31:00+09:00"),
                OffsetDateTime.parse("2026-08-16T09:30:00+09:00"),
            )
    }

    @Test
    fun `fails closed when duplicate boundary values conflict`() {
        val first = parser.parse(
            TossDomesticMinuteCandleRequest("005930"),
            response(candles = candle(close = "72000"), nextBefore = "null"),
        )
        val revised = parser.parse(
            TossDomesticMinuteCandleRequest("005930"),
            response(candles = candle(close = "72001"), nextBefore = "null"),
        )

        assertThatThrownBy { TossCandlePageMerger.merge(listOf(first, revised)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("conflicting values")
    }

    @Test
    fun `stops pagination when the cursor does not move backward`() {
        val current = TossCandleCursor.parse("2026-08-16T09:31:00+09:00")
        val page = TossMinuteCandlePage(
            symbol = "005930",
            candles = emptyList(),
            nextBefore = current,
        )

        assertThatThrownBy { TossCandlePagination.nextBefore(current, page) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Toss nextBefore must move to an earlier instant")
    }

    @Test
    fun `returns a progressing cursor and terminates on the last page`() {
        val firstCursor = TossCandleCursor.parse("2026-08-16T09:31:00+09:00")
        val olderCursor = TossCandleCursor.parse("2026-08-16T09:30:00+09:00")
        val progressingPage = TossMinuteCandlePage("005930", emptyList(), olderCursor)
        val lastPage = TossMinuteCandlePage("005930", emptyList(), null)

        assertThat(TossCandlePagination.nextBefore(null, progressingPage)).isEqualTo(olderCursor)
        assertThat(TossCandlePagination.nextBefore(firstCursor, progressingPage)).isEqualTo(olderCursor)
        assertThat(TossCandlePagination.nextBefore(olderCursor, lastPage)).isNull()
    }

    private fun response(candles: String, nextBefore: String): String = """
        {
          "result": {
            "candles": [$candles],
            "nextBefore": $nextBefore
          }
        }
    """.trimIndent()

    private fun candle(
        timestamp: String = "2026-08-16T09:31:00+09:00",
        open: String = "71950",
        high: String = "72050",
        low: String = "71900",
        close: String = "72000",
        volume: String = "18400",
        currency: String = "KRW",
    ): String = """
        {
          "timestamp": "$timestamp",
          "openPrice": "$open",
          "highPrice": "$high",
          "lowPrice": "$low",
          "closePrice": "$close",
          "volume": "$volume",
          "currency": "$currency"
        }
    """.trimIndent()
}
