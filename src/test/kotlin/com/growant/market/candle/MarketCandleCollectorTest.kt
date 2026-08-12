package com.growant.market.candle

import com.growant.market.port.InstrumentCatalog
import com.growant.market.port.Subscription
import com.growant.market.port.Tick
import com.growant.market.port.TradeTickSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verifyNoInteractions
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MarketCandleCollectorTest {
    private val provider = RecordingProvider()
    private val ingestion = mock(MinuteCandleIngestionService::class.java)
    private val catalog = InstrumentCatalog { it in CandleProperties.DEFAULT_TRACKED_TICKERS }
    private val collector = MarketCandleCollector(provider, catalog, ingestion)

    @Test
    fun `subscribes the configured five catalog tickers once`() {
        collector.start()
        collector.start()

        assertThat(provider.subscribed).containsExactly("005930", "000660", "035720", "035420", "005380")
    }

    @Test
    fun `normalizes provider ticks before ingestion`() {
        val expected = TradeTick(
            ticker = "005930",
            price = 70_100,
            quantity = 25,
            occurredAt = Instant.parse("2026-08-10T00:00:10Z"),
            sequence = 7,
        )
        given(ingestion.acceptTick(expected)).willReturn(TickAcceptance.ACCEPTED)
        collector.start()
        provider.emit(
            Tick(
                ticker = "005930",
                price = BigDecimal("70100"),
                changeRate = 1.2,
                epochMillis = Instant.parse("2026-08-10T00:00:10Z").toEpochMilli(),
                quantity = 25,
                sequence = 7,
            ),
        )

        val acceptedTick = mockingDetails(ingestion).invocations
            .single { invocation -> invocation.method.name == "acceptTick" }
            .arguments.single() as TradeTick
        assertThat(acceptedTick).isEqualTo(expected)
    }

    @Test
    fun `ignores a provider tick routed to the wrong subscription`() {
        collector.start()
        provider.emitFor(
            "005930",
            Tick(
                ticker = "000660",
                price = BigDecimal("178500"),
                changeRate = 0.0,
                epochMillis = Instant.parse("2026-08-10T00:00:10Z").toEpochMilli(),
                quantity = 1,
                sequence = 8,
            ),
        )

        verifyNoInteractions(ingestion)
    }

    @Test
    fun `unsubscribes tracked tickers on shutdown`() {
        collector.start()

        collector.stop()

        assertThat(provider.closed).containsExactlyElementsOf(provider.subscribed.asReversed())
    }

    @Test
    fun `retries a subscription that failed to close`() {
        collector.start()
        provider.failCloseOnceFor = "035720"

        collector.stop()
        collector.stop()

        assertThat(provider.closeAttempts.count { it == "035720" }).isEqualTo(2)
        assertThat(provider.activeTickers()).isEmpty()
    }

    @Test
    fun `finalizes candles using the configured delay and injected clock`() {
        val now = Instant.parse("2026-08-10T00:01:05Z")
        val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
        val configuredCollector = MarketCandleCollector(
            provider,
            catalog,
            ingestion,
            MinuteCandlePolicy(CandleProperties()),
            fixedClock,
        )

        configuredCollector.finalizeCompletedCandles()

        assertThat(mockingDetails(ingestion).invocations)
            .anySatisfy { invocation ->
                assertThat(invocation.method.name).isEqualTo("finalizeBefore")
                assertThat(invocation.arguments.single()).isEqualTo(Instant.parse("2026-08-10T00:01:00Z"))
            }
    }

    @Test
    fun `rolls back acquired subscriptions when one ticker fails and allows retry`() {
        provider.failOnTicker = "035720"

        org.assertj.core.api.Assertions.assertThatThrownBy(collector::start)
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(provider.closed).containsExactly("000660", "005930")

        provider.failOnTicker = null
        collector.start()

        assertThat(provider.activeTickers()).containsExactlyInAnyOrderElementsOf(CandleProperties.DEFAULT_TRACKED_TICKERS)
    }

    private class RecordingProvider : TradeTickSource {
        val subscribed = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val closeAttempts = mutableListOf<String>()
        var failOnTicker: String? = null
        var failCloseOnceFor: String? = null
        private val callbacks = mutableMapOf<String, (Tick) -> Unit>()

        override fun subscribe(ticker: String, onTick: (Tick) -> Unit): Subscription {
            if (ticker == failOnTicker) throw IllegalStateException("subscription failed")
            subscribed += ticker
            callbacks[ticker] = onTick
            return Subscription {
                closeAttempts += ticker
                if (failCloseOnceFor == ticker) {
                    failCloseOnceFor = null
                    throw IllegalStateException("close failed")
                }
                closed += ticker
                callbacks.remove(ticker)
            }
        }

        fun emit(tick: Tick) {
            checkNotNull(callbacks[tick.ticker]).invoke(tick)
        }

        fun emitFor(subscriptionTicker: String, tick: Tick) {
            checkNotNull(callbacks[subscriptionTicker]).invoke(tick)
        }

        fun activeTickers(): List<String> = callbacks.keys.toList()
    }
}
