package com.growant.market.candle

import com.growant.market.candle.persistence.MinuteCandleStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class MinuteCandleIngestionService(
    private val store: MinuteCandleStore,
    @Value("\${market.provider:sim}") source: String,
) {
    private val aggregator = MinuteCandleAggregator(source)
    private val aggregatorLock = Any()
    private val pending = linkedMapOf<CandleKey, MinuteCandle>()

    fun accept(tick: TradeTick): Boolean = synchronized(aggregatorLock) {
        aggregator.accept(tick)
    }

    @Synchronized
    fun finalizeBefore(beforeExclusive: Instant): List<MinuteCandle> {
        val candidates = synchronized(aggregatorLock) {
            aggregator.drainFinalized(beforeExclusive).forEach { candle ->
                pending.putIfAbsent(candle.key(), candle)
            }
            pending.values.toList()
        }

        return candidates.onEach { candle ->
            store.upsert(candle)
            synchronized(aggregatorLock) {
                val key = candle.key()
                if (pending[key] == candle) pending.remove(key)
            }
        }
    }

    fun snapshots(): List<MinuteCandle> = synchronized(aggregatorLock) {
        (pending.values + aggregator.snapshots())
            .sortedWith(compareBy({ it.bucketStart }, { it.ticker }))
    }

    @Transactional
    fun reconcile(candle: MinuteCandle): Int {
        require(candle.isFinal) { "reconciled candle must be final" }
        return store.upsert(candle)
    }

    private fun MinuteCandle.key() = CandleKey(ticker, bucketStart)

    private data class CandleKey(
        val ticker: String,
        val bucketStart: Instant,
    )
}
