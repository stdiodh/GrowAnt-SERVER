package com.growant.market.candle

import com.growant.market.candle.persistence.MinuteCandleStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Instant

class MinuteCandleIngestionServiceTest {
    private val store = mock(MinuteCandleStore::class.java)
    private val service = MinuteCandleIngestionService(store, "test-feed")

    @Test
    fun `keeps a forming candle out of PostgreSQL`() {
        service.accept(tick("2026-08-10T00:00:10Z", 100, 3, 1))

        val snapshot = service.snapshots().single()
        assertThat(snapshot.close).isEqualTo(100)
        assertThat(snapshot.isFinal).isFalse()
        verifyNoInteractions(store)
    }

    @Test
    fun `persists a candle once its minute is complete`() {
        service.accept(tick("2026-08-10T00:00:10Z", 100, 3, 1))
        service.accept(tick("2026-08-10T00:00:40Z", 110, 5, 2))

        val finalized = service.finalizeBefore(Instant.parse("2026-08-10T00:01:00Z")).single()

        assertThat(finalized.close).isEqualTo(110)
        assertThat(finalized.volume).isEqualTo(8)
        assertThat(finalized.isFinal).isTrue()
        verify(store).upsert(finalized)
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
        given(store.upsert(expected)).willThrow(IllegalStateException("store unavailable"))

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
        given(store.upsert(corrected)).willReturn(1)

        assertThat(service.reconcile(corrected)).isEqualTo(1)
        verify(store).upsert(corrected)
    }

    private fun tick(time: String, price: Int, quantity: Long, sequence: Long) = TradeTick(
        ticker = "005930",
        price = price,
        quantity = quantity,
        occurredAt = Instant.parse(time),
        sequence = sequence,
    )
}
