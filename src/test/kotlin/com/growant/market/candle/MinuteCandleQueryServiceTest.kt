package com.growant.market.candle

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.market.MarketService
import com.growant.market.candle.persistence.MinuteCandleStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.time.Instant

class MinuteCandleQueryServiceTest {
    private val store = mock(MinuteCandleStore::class.java)
    private val service = MinuteCandleQueryService(MarketService(), store)

    @Test
    fun `returns one-minute candles in the shared response model`() {
        val from = Instant.parse("2026-08-10T00:00:00Z")
        val to = Instant.parse("2026-08-10T00:02:00Z")
        given(store.find("005930", from, to)).willReturn(
            listOf(
                MinuteCandle(
                    ticker = "005930",
                    bucketStart = from,
                    open = 70_000,
                    high = 70_200,
                    low = 69_900,
                    close = 70_100,
                    volume = 1234,
                    tradeCount = 12,
                    revision = 1,
                    isFinal = true,
                    source = "replay",
                ),
            ),
        )

        val result = service.getCandles("005930", from, to)

        assertThat(result.ticker).isEqualTo("005930")
        assertThat(result.interval).isEqualTo("1m")
        assertThat(result.timezone).isEqualTo("Asia/Seoul")
        assertThat(result.candles.single().time.toString()).isEqualTo("2026-08-10T09:00+09:00")
        assertThat(result.candles.single().close).isEqualTo(70_100)
        assertThat(result.candles.single().final).isTrue()
    }

    @Test
    fun `rejects unknown ticker`() {
        assertThatThrownBy {
            service.getCandles(
                "999999",
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-10T00:01:00Z"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.INVALID_TICKER) })
    }

    @Test
    fun `rejects a catalog ticker outside the tracked five`() {
        assertThatThrownBy {
            service.getCandles(
                "000270",
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-10T00:01:00Z"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .satisfies({
                assertThat((it as BusinessException).code).isEqualTo(ErrorCode.CANDLE_TICKER_NOT_TRACKED)
            })
    }

    @Test
    fun `rejects an inverted range`() {
        assertThatThrownBy {
            service.getCandles(
                "005930",
                Instant.parse("2026-08-10T00:01:00Z"),
                Instant.parse("2026-08-10T00:00:00Z"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.INVALID_CANDLE_RANGE) })
    }

    @Test
    fun `rejects a range longer than seven days`() {
        assertThatThrownBy {
            service.getCandles(
                "005930",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:00Z"),
            )
        }.isInstanceOf(BusinessException::class.java)
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.INVALID_CANDLE_RANGE) })
    }
}
