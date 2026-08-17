package com.growant.market.kis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class KisMarketDataContractsTest {
    private val candleParser = KisMinuteCandleResponseParser()

    @Test
    fun `parses bundled H0STCNT0 rows in wire order`() {
        val first = tradeRow(price = "73100", quantity = "12", accumulatedVolume = "1000")
        val second = tradeRow(price = "73200", quantity = "7", accumulatedVolume = "1007")
        val frame = "0|H0STCNT0|002|${(first + second).joinToString("^")}"

        val trades = KisH0stcnt0FrameParser.parse(frame)

        assertThat(trades).hasSize(2)
        assertThat(trades.map { it.wireOrdinal }).containsExactly(0, 1)
        assertThat(trades.map { it.reportedTicker }).containsExactly("005930", "005930")
        assertThat(trades.map { it.businessDate }).containsOnly(LocalDate.of(2026, 8, 16))
        assertThat(trades.map { it.tradeTime }).containsOnly(LocalTime.of(9, 30, 1))
        assertThat(trades[0].price).isEqualByComparingTo(BigDecimal("73100"))
        assertThat(trades[0].quantity).isEqualByComparingTo(BigDecimal("12"))
        assertThat(trades[1].accumulatedVolume).isEqualByComparingTo(BigDecimal("1007"))
        assertThat(trades[0].marketOperationCode).isEqualTo("20")
        assertThat(trades[0].hourClassCode).isEqualTo("A")
    }

    @Test
    fun `keeps multiple trades from the same provider second`() {
        val rows = tradeRow(price = "73100") + tradeRow(price = "73200")

        val trades = KisH0stcnt0FrameParser.parse("0|H0STCNT0|2|${rows.joinToString("^")}")

        assertThat(trades).extracting("tradeTime", "price")
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(LocalTime.of(9, 30, 1), BigDecimal("73100")),
                org.assertj.core.groups.Tuple.tuple(LocalTime.of(9, 30, 1), BigDecimal("73200")),
            )
    }

    @Test
    fun `does not treat daily OHLC fields as minute trade data`() {
        val row = tradeRow(price = "73100").toMutableList().apply {
            this[7] = "70000"
            this[8] = "80000"
            this[9] = "60000"
        }

        val trade = KisH0stcnt0FrameParser.parse("0|H0STCNT0|001|${row.joinToString("^")}").single()

        assertThat(trade.price).isEqualByComparingTo(BigDecimal("73100"))
    }

    @Test
    fun `rejects encrypted and wrong TR frames`() {
        val payload = tradeRow().joinToString("^")

        assertThatThrownBy { KisH0stcnt0FrameParser.parse("1|H0STCNT0|001|$payload") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS H0STCNT0 encrypted frame requires decryption before parsing")

        assertThatThrownBy { KisH0stcnt0FrameParser.parse("0|H0NXCNT0|001|$payload") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS frame TR ID must be H0STCNT0")
    }

    @Test
    fun `rejects invalid row count and field count`() {
        val payload = tradeRow().joinToString("^")

        assertThatThrownBy { KisH0stcnt0FrameParser.parse("0|H0STCNT0|none|$payload") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS H0STCNT0 row count must be a decimal integer")

        assertThatThrownBy { KisH0stcnt0FrameParser.parse("0|H0STCNT0|002|$payload") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS H0STCNT0 payload must contain 92 fields")

        assertThatThrownBy { KisH0stcnt0FrameParser.parse("0|H0STCNT0|1774008231|a^b") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS H0STCNT0 payload must contain 81604378626 fields")

        assertThatThrownBy { KisH0stcnt0FrameParser.parse("0|H0STCNT0|001|$payload|corrupt") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS H0STCNT0 frame must contain exactly three pipes")
    }

    @Test
    fun `rejects invalid H0STCNT0 time numeric and volume fields`() {
        val invalidTime = tradeRow().toMutableList().apply { this[1] = "250000" }
        assertThatThrownBy {
            KisH0stcnt0FrameParser.parse("0|H0STCNT0|001|${invalidTime.joinToString("^")}")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS H0STCNT0 row[0].STCK_CNTG_HOUR must be a valid time")

        val invalidPrice = tradeRow().toMutableList().apply { this[2] = "not-a-price" }
        assertThatThrownBy {
            KisH0stcnt0FrameParser.parse("0|H0STCNT0|001|${invalidPrice.joinToString("^")}")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS H0STCNT0 row[0].STCK_PRPR must be numeric")

        val negativeVolume = tradeRow().toMutableList().apply { this[12] = "-1" }
        assertThatThrownBy {
            KisH0stcnt0FrameParser.parse("0|H0STCNT0|001|${negativeVolume.joinToString("^")}")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS H0STCNT0 row[0].CNTG_VOL must not be negative")
    }

    @Test
    fun `builds reproducible today and daily KRX request parameters`() {
        assertThat(todayRequest().queryParameters()).containsExactlyEntriesOf(
            linkedMapOf(
                "FID_COND_MRKT_DIV_CODE" to "J",
                "FID_INPUT_ISCD" to "005930",
                "FID_INPUT_HOUR_1" to "093100",
                "FID_PW_DATA_INCU_YN" to "Y",
                "FID_ETC_CLS_CODE" to "",
            ),
        )
        assertThat(dailyRequest().queryParameters()).containsExactlyEntriesOf(
            linkedMapOf(
                "FID_COND_MRKT_DIV_CODE" to "J",
                "FID_INPUT_ISCD" to "005930",
                "FID_INPUT_HOUR_1" to "153000",
                "FID_INPUT_DATE_1" to "20260816",
                "FID_PW_DATA_INCU_YN" to "Y",
                "FID_FAKE_TICK_INCU_YN" to "N",
            ),
        )
    }

    @Test
    fun `parses today and daily REST candles with full request context`() {
        listOf(todayRequest(), dailyRequest()).forEach { request ->

            val response = candleParser.parse(request, minuteResponse())

            assertThat(response.request).isEqualTo(request)
            assertThat(response.request.marketDivision).isEqualTo(KisMarketDivision.KRX)
            assertThat(response.request.endpoint.trId).isEqualTo(
                if (request.endpoint == KisMinuteCandleEndpoint.TODAY) "FHKST03010200" else "FHKST03010230",
            )
            val candle = response.candles.single()
            assertThat(candle.businessDate).isEqualTo(LocalDate.of(2026, 8, 16))
            assertThat(candle.tradeTime).isEqualTo(LocalTime.of(9, 31))
            assertThat(candle.values.openPrice).isEqualByComparingTo(BigDecimal("71950"))
            assertThat(candle.values.highPrice).isEqualByComparingTo(BigDecimal("72050"))
            assertThat(candle.values.lowPrice).isEqualByComparingTo(BigDecimal("71900"))
            assertThat(candle.values.closePrice).isEqualByComparingTo(BigDecimal("72000"))
            assertThat(candle.values.volume).isEqualByComparingTo(BigDecimal("18400"))
            assertThat(candle.accumulatedTradingValue).isEqualByComparingTo(BigDecimal("55731000300"))
            assertThat(candle.wireOrdinal).isZero()
        }
    }

    @Test
    fun `preserves REST wire order instead of assuming newest first`() {
        val first = candleJson(time = "093000", close = "71900")
        val second = candleJson(time = "093200", close = "72100")
        val response = candleParser.parse(
            todayRequest(),
            minuteResponse(candles = "$first,$second"),
        )

        assertThat(response.candles.map { it.tradeTime })
            .containsExactly(LocalTime.of(9, 30), LocalTime.of(9, 32))
        assertThat(response.candles.map { it.wireOrdinal }).containsExactly(0, 1)
    }

    @Test
    fun `accepts a successful empty REST response`() {
        val response = candleParser.parse(
            dailyRequest(),
            minuteResponse(candles = ""),
        )

        assertThat(response.candles).isEmpty()
    }

    @Test
    fun `rejects provider failure and malformed REST fields`() {
        val request = todayRequest()
        val failure = """
            {"rt_cd":"1","msg_cd":"EGW00201","msg1":"failed"}
        """.trimIndent()
        assertThatThrownBy { candleParser.parse(request, failure) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS minute candle response failed: msg_cd=EGW00201")

        val numericClose = minuteResponse(
            candles = candleJson().replace("\"stck_prpr\": \"72000\"", "\"stck_prpr\": 72000"),
        )
        assertThatThrownBy { candleParser.parse(request, numericClose) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS minute candle[0].stck_prpr must be a string")

        val textClose = minuteResponse(
            candles = candleJson().replace("\"stck_prpr\": \"72000\"", "\"stck_prpr\": \"not-a-number\""),
        )
        assertThatThrownBy { candleParser.parse(request, textClose) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS minute candle[0].stck_prpr must be numeric")

        val invalidDate = minuteResponse(
            candles = candleJson().replace("\"stck_bsop_date\": \"20260816\"", "\"stck_bsop_date\": \"20260230\""),
        )
        assertThatThrownBy { candleParser.parse(request, invalidDate) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("KIS minute candle[0].stck_bsop_date must be a valid date")
    }

    @Test
    fun `rejects an invalid domestic request ticker`() {
        assertThatThrownBy {
            KisTodayMinuteCandleRequest("AAPL", KisMarketDivision.KRX, LocalTime.of(9, 31))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("ticker must be a 6-digit domestic ticker")

        assertThatThrownBy {
            KisDailyMinuteCandleRequest(
                "AAPL",
                KisMarketDivision.KRX,
                LocalDate.of(2026, 8, 16),
                LocalTime.of(15, 30),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("ticker must be a 6-digit domestic ticker")
    }

    private fun tradeRow(
        price: String = "73100",
        quantity: String = "12",
        accumulatedVolume: String = "1000",
    ): List<String> = MutableList(46) { "0" }.apply {
        this[0] = "005930"
        this[1] = "093001"
        this[2] = price
        this[12] = quantity
        this[13] = accumulatedVolume
        this[33] = "20260816"
        this[34] = "20"
        this[42] = "42-adjacent"
        this[43] = "A"
        this[44] = "44-adjacent"
    }

    private fun todayRequest() = KisTodayMinuteCandleRequest(
        ticker = "005930",
        marketDivision = KisMarketDivision.KRX,
        inputTime = LocalTime.of(9, 31),
    )

    private fun dailyRequest() = KisDailyMinuteCandleRequest(
        ticker = "005930",
        marketDivision = KisMarketDivision.KRX,
        inputDate = LocalDate.of(2026, 8, 16),
        inputTime = LocalTime.of(15, 30),
    )

    private fun minuteResponse(candles: String = candleJson()): String = """
        {
          "rt_cd": "0",
          "msg_cd": "MCA00000",
          "msg1": "success",
          "output2": [$candles]
        }
    """.trimIndent()

    private fun candleJson(
        time: String = "093100",
        close: String = "72000",
    ): String = """
        {
          "stck_bsop_date": "20260816",
          "stck_cntg_hour": "$time",
          "stck_prpr": "$close",
          "stck_oprc": "71950",
          "stck_hgpr": "72050",
          "stck_lwpr": "71900",
          "cntg_vol": "18400",
          "acml_tr_pbmn": "55731000300"
        }
    """.trimIndent()
}
