package com.growant.market.candle

import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.ZoneId

@WebMvcTest(MinuteCandleController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class)
class MinuteCandleControllerTest(@Autowired val mockMvc: MockMvc) {
    @MockitoBean
    lateinit var service: MinuteCandleQueryService

    @Test
    fun `GET candles returns OHLCV in the common envelope`() {
        val from = Instant.parse("2026-08-10T00:00:00Z")
        val to = Instant.parse("2026-08-10T00:02:00Z")
        given(service.getCandles("005930", from, to)).willReturn(
            MinuteCandleSeries(
                ticker = "005930",
                zoneId = ZoneId.of("Asia/Seoul"),
                candles = listOf(
                    MinuteCandle(
                        ticker = "005930",
                        bucketStart = from,
                        open = 70_000,
                        high = 70_200,
                        low = 69_900,
                        close = 70_100,
                        volume = 1234,
                        tradeCount = 12,
                        isFinal = true,
                        revision = 1,
                        source = "test",
                    ),
                ),
            ),
        )

        mockMvc.get("/api/market/005930/candles") {
            with(jwt())
            param("from", "2026-08-10T00:00:00Z")
            param("to", "2026-08-10T00:02:00Z")
        }.andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.ticker") { value("005930") } }
            .andExpect { jsonPath("$.data.interval") { value("1m") } }
            .andExpect { jsonPath("$.data.timezone") { value("Asia/Seoul") } }
            .andExpect { jsonPath("$.data.candles[0].open") { value(70000) } }
            .andExpect { jsonPath("$.data.candles[0].volume") { value(1234) } }
            .andExpect { jsonPath("$.data.candles[0].final") { value(true) } }
    }

    @Test
    fun `GET candles rejects an invalid range`() {
        val from = Instant.parse("2026-08-10T00:02:00Z")
        val to = Instant.parse("2026-08-10T00:00:00Z")
        given(service.getCandles("005930", from, to))
            .willThrow(BusinessException(ErrorCode.INVALID_CANDLE_RANGE))

        mockMvc.get("/api/market/005930/candles") {
            with(jwt())
            param("from", "2026-08-10T00:02:00Z")
            param("to", "2026-08-10T00:00:00Z")
        }.andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.error.code") { value("INVALID_CANDLE_RANGE") } }
    }

    @Test
    fun `GET candles requires authentication`() {
        mockMvc.get("/api/market/005930/candles") {
            param("from", "2026-08-10T00:00:00Z")
            param("to", "2026-08-10T00:02:00Z")
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET candles rejects a malformed range boundary`() {
        mockMvc.get("/api/market/005930/candles") {
            with(jwt())
            param("from", "not-a-time")
            param("to", "2026-08-10T00:02:00Z")
        }.andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.error.code") { value("INVALID_CANDLE_RANGE") } }
    }

    @Test
    fun `GET candles rejects a missing range boundary`() {
        mockMvc.get("/api/market/005930/candles") {
            with(jwt())
            param("from", "2026-08-10T00:00:00Z")
        }.andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.error.code") { value("INVALID_CANDLE_RANGE") } }
    }
}
