package com.growant.market.candle.persistence

import com.growant.market.candle.MinuteCandle
import com.growant.market.candle.port.MinuteCandleSaveResult
import com.growant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MinuteCandleStoreConcurrencyIT(
    @Autowired private val store: MinuteCandleStore,
) : PostgresIntegrationTest() {
    @Test
    fun `concurrent identical saves are classified as one insert and one unchanged result`() {
        val candle = candle(
            ticker = "CT900000",
            bucketStart = Instant.parse("2026-08-11T00:00:00Z"),
            revision = 0,
            close = 105,
            source = "same-feed",
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val writes = List(2) {
                executor.submit<MinuteCandleSaveResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "timed out waiting for concurrent start" }
                    store.save(candle)
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            assertThat(writes.map { it.get(10, TimeUnit.SECONDS) })
                .containsExactlyInAnyOrder(
                    MinuteCandleSaveResult.INSERTED_OR_UPDATED,
                    MinuteCandleSaveResult.UNCHANGED,
                )
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(store.find(candle.ticker, candle.bucketStart, candle.bucketStart.plusSeconds(60)))
            .containsExactly(candle)
    }

    @Test
    fun `same revision with different payload keeps the first stored candle`() {
        val first = candle(
            ticker = "CT900001",
            bucketStart = Instant.parse("2026-08-11T00:00:00Z"),
            revision = 3,
            close = 105,
            source = "first-feed",
        )
        val conflicting = first.copy(
            close = 109,
            volume = 2_000,
            tradeCount = 20,
            source = "conflicting-feed",
        )

        assertThat(store.upsert(first)).isEqualTo(1)
        assertThat(store.upsert(conflicting)).isZero()

        assertThat(store.find(first.ticker, first.bucketStart, first.bucketStart.plusSeconds(60)))
            .containsExactly(first)
    }

    @Test
    fun `concurrent revisions converge on the highest revision`() {
        val ticker = "CT900002"
        val bucketStart = Instant.parse("2026-08-11T00:01:00Z")
        val revisions = (0..7).map { revision ->
            candle(
                ticker = ticker,
                bucketStart = bucketStart,
                revision = revision,
                close = 100 + revision,
                volume = 1_000L + revision,
                tradeCount = 10L + revision,
                source = "revision-$revision",
            )
        }
        val ready = CountDownLatch(revisions.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(revisions.size)

        try {
            val writes = revisions.map { candle ->
                executor.submit<Int> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "timed out waiting for concurrent start" }
                    store.upsert(candle)
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            writes.forEach { write -> write.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(store.find(ticker, bucketStart, bucketStart.plusSeconds(60)))
            .containsExactly(revisions.last())
    }

    private fun candle(
        ticker: String,
        bucketStart: Instant,
        revision: Int,
        close: Int,
        volume: Long = 1_000,
        tradeCount: Long = 10,
        source: String,
    ) = MinuteCandle(
        ticker = ticker,
        bucketStart = bucketStart,
        open = 100,
        high = 110,
        low = 90,
        close = close,
        volume = volume,
        tradeCount = tradeCount,
        revision = revision,
        isFinal = true,
        source = source,
    )
}
