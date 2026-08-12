package com.growant.market.candle

import com.growant.market.candle.port.MinuteCandleRepository
import com.growant.market.candle.port.MinuteCandleSaveResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MinuteCandleIngestionCharacterizationTest {
    private val repository = mock(MinuteCandleRepository::class.java)
    private val service = MinuteCandleIngestionService(
        repository,
        MinuteCandlePolicy(CandleProperties(source = "test-feed")),
    )

    @Test
    fun `middle persistence failure retains only unpersisted candles and retries them in order`() {
        val first = finalCandle("2026-08-10T00:00:00Z", 100)
        val second = finalCandle("2026-08-10T00:01:00Z", 110)
        val third = finalCandle("2026-08-10T00:02:00Z", 120)
        service.accept(tick("2026-08-10T00:00:10Z", 100, 1))
        service.accept(tick("2026-08-10T00:01:10Z", 110, 2))
        service.accept(tick("2026-08-10T00:02:10Z", 120, 3))
        given(repository.save(first)).willReturn(MinuteCandleSaveResult.INSERTED_OR_UPDATED)
        given(repository.save(second))
            .willThrow(IllegalStateException("second write failed"))
            .willReturn(MinuteCandleSaveResult.INSERTED_OR_UPDATED)
        given(repository.save(third)).willReturn(MinuteCandleSaveResult.INSERTED_OR_UPDATED)

        assertThatThrownBy { service.finalizeBefore(Instant.parse("2026-08-10T00:03:00Z")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("second write failed")

        assertThat(service.snapshots()).containsExactly(second, third)

        assertThat(service.finalizeBefore(Instant.parse("2026-08-10T00:03:00Z")))
            .containsExactly(second, third)
        assertThat(service.snapshots()).isEmpty()
        verify(repository, times(1)).save(first)
        verify(repository, times(2)).save(second)
        verify(repository, times(1)).save(third)
    }

    @Test
    fun `accept remains safe while a completed candle is waiting on persistence`() {
        val completed = finalCandle("2026-08-10T00:00:00Z", 100)
        service.accept(tick("2026-08-10T00:00:10Z", 100, 1))

        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        given(repository.save(completed)).willAnswer {
            writeStarted.countDown()
            check(releaseWrite.await(5, TimeUnit.SECONDS)) { "timed out waiting to release store write" }
            MinuteCandleSaveResult.INSERTED_OR_UPDATED
        }

        val executor = Executors.newFixedThreadPool(3)
        try {
            val finalizeFuture = executor.submit<List<MinuteCandle>> {
                service.finalizeBefore(Instant.parse("2026-08-10T00:01:00Z"))
            }
            assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue()

            val lateAccept = executor.submit<Boolean> {
                service.accept(tick("2026-08-10T00:00:50Z", 90, 2))
            }
            val nextMinuteAccept = executor.submit<Boolean> {
                service.accept(tick("2026-08-10T00:01:10Z", 110, 3))
            }

            assertThat(lateAccept.get(2, TimeUnit.SECONDS)).isFalse()
            assertThat(nextMinuteAccept.get(2, TimeUnit.SECONDS)).isTrue()
            releaseWrite.countDown()

            assertThat(finalizeFuture.get(5, TimeUnit.SECONDS)).containsExactly(completed)
            assertThat(service.snapshots()).containsExactly(
                completed.copy(
                    bucketStart = Instant.parse("2026-08-10T00:01:00Z"),
                    open = 110,
                    high = 110,
                    low = 110,
                    close = 110,
                    isFinal = false,
                ),
            )
        } finally {
            releaseWrite.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun tick(time: String, price: Int, sequence: Long) = TradeTick(
        ticker = "005930",
        price = price,
        quantity = 1,
        occurredAt = Instant.parse(time),
        sequence = sequence,
    )

    private fun finalCandle(bucketStart: String, price: Int) = MinuteCandle(
        ticker = "005930",
        bucketStart = Instant.parse(bucketStart),
        open = price,
        high = price,
        low = price,
        close = price,
        volume = 1,
        tradeCount = 1,
        isFinal = true,
        source = "test-feed",
    )
}
