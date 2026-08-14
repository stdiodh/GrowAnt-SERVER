package com.growant.market.candle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

class CandlePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(CandleConfiguration::class.java)

    @Test
    fun `provides the existing minute candle defaults`() {
        val properties = CandleProperties()

        assertThat(properties.source).isEqualTo("sim")
        assertThat(properties.trackedTickers).containsExactly(
            "005930",
            "000660",
            "035720",
            "035420",
            "005380",
        )
        assertThat(properties.finalizationDelay).isEqualTo(Duration.ofSeconds(5))
        assertThat(properties.schedulerInterval).isEqualTo(Duration.ofSeconds(1))
        assertThat(properties.maxQueryRange).isEqualTo(Duration.ofDays(7))
        assertThat(properties.zoneId).isEqualTo(ZoneId.of("Asia/Seoul"))
    }

    @Test
    fun `rejects duplicate tracked tickers`() {
        assertThatThrownBy { CandleProperties(trackedTickers = listOf("005930", "005930")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("trackedTickers must not contain duplicates")
    }

    @Test
    fun `rejects a source longer than the database column`() {
        assertThatThrownBy { CandleProperties(source = "x".repeat(41)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("source must not exceed 40 characters")
    }

    @Test
    fun `rejects a non-positive maximum query range`() {
        assertThatThrownBy { CandleProperties(maxQueryRange = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("maxQueryRange must be positive")
    }

    @Test
    fun `rejects a non-positive scheduler interval`() {
        assertThatThrownBy { CandleProperties(schedulerInterval = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("schedulerInterval must be positive")
    }

    @Test
    fun `binds external candle settings into the shared policy and clock`() {
        contextRunner
            .withPropertyValues(
                "market.candles.tracked-tickers=005930,000660",
                "market.candles.source=test-feed",
                "market.candles.finalization-delay=9s",
                "market.candles.scheduler-interval=2s",
                "market.candles.max-query-range=2d",
                "market.candles.zone-id=UTC",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val policy = context.getBean(MinuteCandlePolicy::class.java)
                assertThat(policy.source).isEqualTo("test-feed")
                assertThat(policy.trackedTickers).containsExactly("005930", "000660")
                assertThat(policy.finalizationDelay).isEqualTo(Duration.ofSeconds(9))
                assertThat(policy.schedulerInterval).isEqualTo(Duration.ofSeconds(2))
                assertThat(policy.maxQueryRange).isEqualTo(Duration.ofDays(2))
                assertThat(context.getBean(Clock::class.java).zone).isEqualTo(ZoneId.of("UTC"))
            }
    }
}
