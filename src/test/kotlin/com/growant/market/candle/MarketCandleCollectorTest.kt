package com.growant.market.candle

import com.growant.market.MarketService
import com.growant.market.port.MarketDataProvider
import com.growant.market.port.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verifyNoInteractions
import java.math.BigDecimal
import java.time.Instant

class MarketCandleCollectorTest {
    private val provider = RecordingProvider()
    private val ingestion = mock(MinuteCandleIngestionService::class.java)
    private val collector = MarketCandleCollector(provider, MarketService(), ingestion)

    @Test
    fun `subscribes the configured five catalog tickers once`() {
        collector.start()
        collector.start()

        assertThat(provider.subscribed).containsExactly("005930", "000660", "035720", "035420", "005380")
    }

    @Test
    fun `normalizes provider ticks before ingestion`() {
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
            .single { invocation -> invocation.method.name == "accept" }
            .arguments.single() as TradeTick
        assertThat(acceptedTick).isEqualTo(
            TradeTick(
                ticker = "005930",
                price = 70_100,
                quantity = 25,
                occurredAt = Instant.parse("2026-08-10T00:00:10Z"),
                sequence = 7,
            ),
        )
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

        assertThat(provider.unsubscribed).containsExactlyElementsOf(provider.subscribed)
    }

    @Test
    fun `finalizes candles independently from incoming ticks`() {
        collector.finalizeCompletedCandles()

        assertThat(mockingDetails(ingestion).invocations)
            .anySatisfy { invocation ->
                assertThat(invocation.method.name).isEqualTo("finalizeBefore")
                assertThat(invocation.arguments.single()).isInstanceOf(Instant::class.java)
            }
    }

    private class RecordingProvider : MarketDataProvider {
        val subscribed = mutableListOf<String>()
        val unsubscribed = mutableListOf<String>()
        private val callbacks = mutableMapOf<String, (Tick) -> Unit>()

        override fun currentPrice(ticker: String): BigDecimal = BigDecimal.ONE

        override fun subscribe(ticker: String, onTick: (Tick) -> Unit) {
            subscribed += ticker
            callbacks[ticker] = onTick
        }

        override fun unsubscribe(ticker: String) {
            unsubscribed += ticker
        }

        fun emit(tick: Tick) {
            checkNotNull(callbacks[tick.ticker]).invoke(tick)
        }

        fun emitFor(subscriptionTicker: String, tick: Tick) {
            checkNotNull(callbacks[subscriptionTicker]).invoke(tick)
        }
    }
}
