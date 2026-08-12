package com.growant.market.candle

import com.growant.market.candle.port.MinuteCandleRepository
import com.growant.market.candle.port.MinuteCandleSaveResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Instant

class MinuteCandleIngestionServiceTest {
    private val repository = mock(MinuteCandleRepository::class.java)
    private val service = MinuteCandleIngestionService(
        repository,
        MinuteCandlePolicy(CandleProperties(source = "test-feed")),
    )

    @Test
    fun `keeps a forming candle out of PostgreSQL`() {
        service.accept(tick("2026-08-10T00:00:10Z", 100, 3, 1))

        val snapshot = service.snapshots().single()
        assertThat(snapshot.close).isEqualTo(100)
        assertThat(snapshot.isFinal).isFalse()
        verifyNoInteractions(repository)
    }

    @Test
    fun `exposes duplicate acceptance without changing the boolean compatibility method`() {
        val tick = tick("2026-08-10T00:00:10Z", 100, 3, 1)

        assertThat(service.acceptTick(tick)).isEqualTo(TickAcceptance.ACCEPTED)
        assertThat(service.acceptTick(tick)).isEqualTo(TickAcceptance.DUPLICATE)
        assertThat(service.accept(tick)).isFalse()
    }

    @Test
    fun `persists a candle once its minute is complete`() {
        service.accept(tick("2026-08-10T00:00:10Z", 100, 3, 1))
        service.accept(tick("2026-08-10T00:00:40Z", 110, 5, 2))

        val finalized = service.finalizeBefore(Instant.parse("2026-08-10T00:01:00Z")).single()

        assertThat(finalized.close).isEqualTo(110)
        assertThat(finalized.volume).isEqualTo(8)
        assertThat(finalized.isFinal).isTrue()
        verify(repository).save(finalized)
    }

    @Test
    fun `retains a completed candle when persistence fails`() {
        val expected = MinuteCandle(
            ticker = "005930",
            bucketStart = Instant.parse("2026-08-10T00:00:00Z"),
            open = 100,
            high = 100,
            low = 100,
            close = 100,
            volume = 3,
            tradeCount = 1,
            isFinal = true,
            source = "test-feed",
        )
        service.accept(tick("2026-08-10T00:00:10Z", 100, 3, 1))
        given(repository.save(expected)).willThrow(IllegalStateException("store unavailable"))

        assertThatThrownBy { service.finalizeBefore(Instant.parse("2026-08-10T00:01:00Z")) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(service.snapshots()).hasSize(1)
    }

    @Test
    fun `reconcile accepts only a final candle`() {
        val forming = MinuteCandle(
            ticker = "005930",
            bucketStart = Instant.parse("2026-08-10T00:00:00Z"),
            open = 100,
            high = 100,
            low = 100,
            close = 100,
            volume = 1,
            tradeCount = 1,
            isFinal = false,
            source = "rest",
        )

        assertThatThrownBy { service.reconcile(forming) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("reconciled candle must be final")
    }

    @Test
    fun `reconcile delegates a revised final candle to the store`() {
        val corrected = MinuteCandle(
            ticker = "005930",
            bucketStart = Instant.parse("2026-08-10T00:00:00Z"),
            open = 100,
            high = 120,
            low = 90,
            close = 110,
            volume = 10,
            tradeCount = 3,
            revision = 1,
            isFinal = true,
            source = "rest",
        )
        given(repository.save(corrected)).willReturn(MinuteCandleSaveResult.INSERTED_OR_UPDATED)

        assertThat(service.reconcile(corrected)).isEqualTo(1)
        verify(repository).save(corrected)
    }

    @Test
    fun `keeps a conflicting candle pending and exposes the revision conflict`() {
        val expected = MinuteCandle(
            ticker = "005930",
            bucketStart = Instant.parse("2026-08-10T00:00:00Z"),
            open = 100,
            high = 100,
            low = 100,
            close = 100,
            volume = 3,
            tradeCount = 1,
            isFinal = true,
            source = "test-feed",
        )
        service.accept(tick("2026-08-10T00:00:10Z", 100, 3, 1))
        given(repository.save(expected)).willReturn(MinuteCandleSaveResult.REVISION_CONFLICT)

        assertThatThrownBy { service.finalizeBefore(Instant.parse("2026-08-10T00:01:00Z")) }
            .isInstanceOf(MinuteCandleRevisionConflictException::class.java)
            .hasMessageContaining("ticker=005930")
        assertThat(service.snapshots()).containsExactly(expected)
    }

    @Test
    fun `persists later candles even when an earlier pending candle conflicts`() {
        val first = finalCandle("2026-08-10T00:00:00Z", 100)
        val second = finalCandle("2026-08-10T00:01:00Z", 110)
        service.accept(tick("2026-08-10T00:00:10Z", 100, 1, 1))
        service.accept(tick("2026-08-10T00:01:10Z", 110, 1, 2))
        given(repository.save(first)).willReturn(MinuteCandleSaveResult.REVISION_CONFLICT)
        given(repository.save(second)).willReturn(MinuteCandleSaveResult.INSERTED_OR_UPDATED)

        assertThatThrownBy { service.finalizeBefore(Instant.parse("2026-08-10T00:02:00Z")) }
            .isInstanceOf(MinuteCandleRevisionConflictException::class.java)

        verify(repository).save(first)
        verify(repository).save(second)
        assertThat(service.snapshots()).containsExactly(first)
    }

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

    private fun tick(time: String, price: Int, quantity: Long, sequence: Long) = TradeTick(
        ticker = "005930",
        price = price,
        quantity = quantity,
        occurredAt = Instant.parse(time),
        sequence = sequence,
    )
}
