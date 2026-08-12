package com.growant.market.candle

import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

@WebMvcTest(MinuteCandleController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class)
class MinuteCandleApiContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @MockitoBean
    lateinit var service: MinuteCandleQueryService

    @Test
    fun `success response preserves every candle field and JSON type`() {
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
                        volume = 1_234,
                        tradeCount = 12,
                        isFinal = true,
                        revision = 3,
                        source = "test",
                    ),
                ),
            ),
        )

        val result = mockMvc.get("/api/market/005930/candles") {
            with(jwt())
            param("from", "2026-08-10T09:00:00+09:00")
            param("to", "2026-08-10T09:02:00+09:00")
        }.andExpect {
            status { isOk() }
            content {
                json(
                    """
                    {
                      "success": true,
                      "data": {
                        "ticker": "005930",
                        "interval": "1m",
                        "timezone": "Asia/Seoul",
                        "candles": [
                          {
                            "time": "2026-08-10T09:00:00+09:00",
                            "open": 70000,
                            "high": 70200,
                            "low": 69900,
                            "close": 70100,
                            "volume": 1234,
                            "tradeCount": 12,
                            "final": true,
                            "revision": 3
                          }
                        ]
                      },
                      "error": null
                    }
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                )
            }
        }.andReturn()

        val root = objectMapper.readTree(result.response.contentAsString)
        assertThat(root.propertyNames()).containsExactlyInAnyOrder("success", "data", "error")
        assertThat(root.path("success").isBoolean).isTrue()
        assertThat(root.path("error").isNull).isTrue()

        val data = root.path("data")
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("ticker", "interval", "timezone", "candles")
        assertThat(data.path("ticker").isString).isTrue()
        assertThat(data.path("interval").isString).isTrue()
        assertThat(data.path("timezone").isString).isTrue()
        assertThat(data.path("candles").isArray).isTrue()

        val candle = data.path("candles").path(0)
        assertThat(candle.propertyNames()).containsExactlyInAnyOrder(
            "time",
            "open",
            "high",
            "low",
            "close",
            "volume",
            "tradeCount",
            "final",
            "revision",
        )
        assertThat(candle.path("time").isString).isTrue()
        listOf("open", "high", "low", "close", "volume", "tradeCount", "revision").forEach { field ->
            assertThat(candle.path(field).isIntegralNumber).describedAs(field).isTrue()
        }
        assertThat(candle.path("final").isBoolean).isTrue()
        verify(service).getCandles("005930", from, to)
    }

    @Test
    fun `authentication failure preserves the common error envelope`() {
        val result = mockMvc.get("/api/market/005930/candles") {
            param("from", "2026-08-10T00:00:00Z")
            param("to", "2026-08-10T00:02:00Z")
        }.andExpect {
            status { isUnauthorized() }
        }.andReturn()

        assertErrorEnvelope(
            json = result.response.contentAsString,
            code = "UNAUTHENTICATED",
            errorCode = 2000,
            eventType = "AUTH_ERROR",
            message = "로그인이 필요합니다.",
            retryable = false,
        )
        verifyNoInteractions(service)
    }

    @Test
    fun `malformed range preserves the validation error envelope`() {
        val result = mockMvc.get("/api/market/005930/candles") {
            with(jwt())
            param("from", "not-a-time")
            param("to", "2026-08-10T00:02:00Z")
        }.andExpect {
            status { isBadRequest() }
        }.andReturn()

        assertErrorEnvelope(
            json = result.response.contentAsString,
            code = "INVALID_CANDLE_RANGE",
            errorCode = 3003,
            eventType = "VALIDATION_ERROR",
            message = "분봉 조회 기간이 올바르지 않습니다.",
            retryable = false,
        )
        verifyNoInteractions(service)
    }

    private fun assertErrorEnvelope(
        json: String,
        code: String,
        errorCode: Int,
        eventType: String,
        message: String,
        retryable: Boolean,
    ) {
        val root = objectMapper.readTree(json)
        assertThat(root.propertyNames()).containsExactlyInAnyOrder("success", "data", "error")
        assertThat(root.path("success").isBoolean).isTrue()
        assertThat(root.path("success").booleanValue()).isFalse()
        assertThat(root.path("data").isNull).isTrue()

        val error = root.path("error")
        assertThat(error.propertyNames()).containsExactlyInAnyOrder(
            "code",
            "errorCode",
            "eventType",
            "message",
            "retryable",
            "timestamp",
            "traceId",
        )
        assertText(error, "code", code)
        assertThat(error.path("errorCode").isIntegralNumber).isTrue()
        assertThat(error.path("errorCode").intValue()).isEqualTo(errorCode)
        assertText(error, "eventType", eventType)
        assertText(error, "message", message)
        assertThat(error.path("retryable").isBoolean).isTrue()
        assertThat(error.path("retryable").booleanValue()).isEqualTo(retryable)
        assertThat(error.path("timestamp").isString).isTrue()
        OffsetDateTime.parse(error.path("timestamp").stringValue())
        assertThat(error.path("traceId").isString).isTrue()
        UUID.fromString(error.path("traceId").stringValue())
    }

    private fun assertText(node: JsonNode, field: String, expected: String) {
        assertThat(node.path(field).isString).describedAs(field).isTrue()
        assertThat(node.path(field).stringValue()).isEqualTo(expected)
    }
}
