package com.growant.market.candle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class MinuteCandleAggregatorTest {
    private val aggregator = MinuteCandleAggregator(source = "test-feed")

    @Test
    fun `aggregates out-of-order ticks by event time`() {
        aggregator.accept(tick(price = 120, quantity = 2, occurredAt = "2026-08-09T00:00:40Z", sequence = 3))
        aggregator.accept(tick(price = 100, quantity = 5, occurredAt = "2026-08-09T00:00:10Z", sequence = 1))
        aggregator.accept(tick(price = 130, quantity = 7, occurredAt = "2026-08-09T00:00:30Z", sequence = 2))

        assertThat(aggregator.snapshots()).containsExactly(
            MinuteCandle(
                ticker = "005930",
                bucketStart = Instant.parse("2026-08-09T00:00:00Z"),
                open = 100,
                high = 130,
                low = 100,
                close = 120,
                volume = 14,
                tradeCount = 3,
                isFinal = false,
                source = "test-feed",
            ),
        )
    }

    @Test
    fun `uses sequence to choose open and close for ticks at the same time`() {
        val occurredAt = "2026-08-09T00:00:10Z"
        aggregator.accept(tick(price = 300, quantity = 1, occurredAt = occurredAt, sequence = 20))
        aggregator.accept(tick(price = 200, quantity = 1, occurredAt = occurredAt, sequence = 30))
        aggregator.accept(tick(price = 100, quantity = 1, occurredAt = occurredAt, sequence = 10))

        val candle = aggregator.snapshots().single()
        assertThat(candle.open).isEqualTo(100)
        assertThat(candle.high).isEqualTo(300)
        assertThat(candle.low).isEqualTo(100)
        assertThat(candle.close).isEqualTo(200)
    }

    @Test
    fun `ignores an exact duplicate tick`() {
        val tick = tick(price = 100, quantity = 3, occurredAt = "2026-08-09T00:00:10Z", sequence = 1)

        assertThat(aggregator.accept(tick)).isTrue()
        assertThat(aggregator.accept(tick)).isFalse()

        val candle = aggregator.snapshots().single()
        assertThat(candle.volume).isEqualTo(3)
        assertThat(candle.tradeCount).isEqualTo(1)
    }

    @Test
    fun `uses event time and sequence as the duplicate identity`() {
        aggregator.accept(tick(price = 100, quantity = 3, occurredAt = "2026-08-09T00:00:10Z", sequence = 1))

        assertThat(
            aggregator.accept(tick(price = 110, quantity = 5, occurredAt = "2026-08-09T00:00:10Z", sequence = 1)),
        ).isFalse()

        val candle = aggregator.snapshots().single()
        assertThat(candle.close).isEqualTo(100)
        assertThat(candle.volume).isEqualTo(3)
    }

    @Test
    fun `separates minute boundaries and drains only completed buckets`() {
        aggregator.accept(tick(price = 100, occurredAt = "2026-08-09T00:00:59.999Z"))
        aggregator.accept(tick(price = 110, occurredAt = "2026-08-09T00:01:00Z"))

        assertThat(aggregator.drainFinalized(Instant.parse("2026-08-09T00:00:59.999Z"))).isEmpty()

        val finalized = aggregator.drainFinalized(Instant.parse("2026-08-09T00:01:00Z")).single()
        assertThat(finalized.bucketStart).isEqualTo(Instant.parse("2026-08-09T00:00:00Z"))
        assertThat(finalized.isFinal).isTrue()

        val snapshot = aggregator.snapshots().single()
        assertThat(snapshot.bucketStart).isEqualTo(Instant.parse("2026-08-09T00:01:00Z"))
        assertThat(snapshot.isFinal).isFalse()
    }

    @Test
    fun `rejects a tick after its minute passed the finalization watermark`() {
        aggregator.accept(tick(price = 100, occurredAt = "2026-08-09T00:00:10Z"))
        aggregator.drainFinalized(Instant.parse("2026-08-09T00:01:05Z"))

        assertThat(aggregator.accept(tick(price = 110, occurredAt = "2026-08-09T00:00:50Z"))).isFalse()
        assertThat(aggregator.snapshots()).isEmpty()
    }

    @Test
    fun `keeps a finalized candle when persistence fails before removal`() {
        aggregator.accept(tick(price = 100, occurredAt = "2026-08-09T00:00:10Z"))

        assertThatThrownBy {
            aggregator.drainFinalized(Instant.parse("2026-08-09T00:01:00Z")) { error("store unavailable") }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(aggregator.snapshots()).hasSize(1)
    }

    @Test
    fun `keeps ticker buckets independent`() {
        aggregator.accept(tick(ticker = "005930", price = 100, quantity = 2))
        aggregator.accept(tick(ticker = "000660", price = 200, quantity = 3))
        aggregator.accept(tick(ticker = "005930", price = 110, quantity = 5, sequence = 2))

        assertThat(aggregator.snapshots()).extracting("ticker", "close", "volume", "tradeCount")
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("000660", 200, 3L, 1L),
                org.assertj.core.groups.Tuple.tuple("005930", 110, 7L, 2L),
            )
    }

    @Test
    fun `rejects blank ticker`() {
        assertThatThrownBy { aggregator.accept(tick(ticker = " ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("ticker must not be blank")
    }

    @Test
    fun `rejects non-positive price`() {
        assertThatThrownBy { aggregator.accept(tick(price = 0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("price must be positive")
    }

    @Test
    fun `rejects non-positive quantity`() {
        assertThatThrownBy { aggregator.accept(tick(quantity = 0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("quantity must be positive")
    }

    private fun tick(
        ticker: String = "005930",
        price: Int = 100,
        quantity: Long = 1,
        occurredAt: String = "2026-08-09T00:00:10Z",
        sequence: Long = 1,
    ) = TradeTick(
        ticker = ticker,
        price = price,
        quantity = quantity,
        occurredAt = Instant.parse(occurredAt),
        sequence = sequence,
    )
}
