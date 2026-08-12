package com.growant.market.candle

import com.growant.market.candle.port.MinuteCandleRepository
import com.growant.market.candle.port.MinuteCandleSaveResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class MinuteCandleIngestionService(
    private val repository: MinuteCandleRepository,
    policy: MinuteCandlePolicy,
) {
    private val aggregator = MinuteCandleAggregator(policy.source)
    private val aggregatorLock = Any()
    private val pending = linkedMapOf<CandleKey, MinuteCandle>()

    fun accept(tick: TradeTick): Boolean = acceptTick(tick).accepted

    fun acceptTick(tick: TradeTick): TickAcceptance = synchronized(aggregatorLock) {
        aggregator.acceptTick(tick)
    }

    private val finalizationLock = Any()

    fun finalizeBefore(beforeExclusive: Instant): List<MinuteCandle> = synchronized(finalizationLock) {
        val candidates = synchronized(aggregatorLock) {
            aggregator.drainFinalized(beforeExclusive).forEach { candle ->
                pending.putIfAbsent(candle.key(), candle)
            }
            pending.values.toList()
        }

        var firstConflict: MinuteCandleRevisionConflictException? = null
        candidates.forEach { candle ->
            try {
                saveOrThrow(candle)
                synchronized(aggregatorLock) {
                    val key = candle.key()
                    if (pending[key] == candle) pending.remove(key)
                }
            } catch (conflict: MinuteCandleRevisionConflictException) {
                if (firstConflict == null) {
                    firstConflict = conflict
                } else {
                    firstConflict.addSuppressed(conflict)
                }
            }
        }
        firstConflict?.let { throw it }
        return candidates
    }

    fun snapshots(): List<MinuteCandle> = synchronized(aggregatorLock) {
        (pending.values + aggregator.snapshots())
            .sortedWith(compareBy({ it.bucketStart }, { it.ticker }))
    }

    @Transactional
    fun reconcile(candle: MinuteCandle): Int {
        require(candle.isFinal) { "reconciled candle must be final" }
        return when (saveOrThrow(candle)) {
            MinuteCandleSaveResult.INSERTED_OR_UPDATED -> 1
            MinuteCandleSaveResult.UNCHANGED,
            MinuteCandleSaveResult.STALE_REVISION,
            -> 0
            MinuteCandleSaveResult.REVISION_CONFLICT -> error("unreachable")
        }
    }

    private fun saveOrThrow(candle: MinuteCandle): MinuteCandleSaveResult =
        repository.save(candle).also { result ->
            if (result == MinuteCandleSaveResult.REVISION_CONFLICT) {
                throw MinuteCandleRevisionConflictException(candle.ticker, candle.bucketStart, candle.revision)
            }
        }

    private fun MinuteCandle.key() = CandleKey(ticker, bucketStart)

    private data class CandleKey(
        val ticker: String,
        val bucketStart: Instant,
    )
}

class MinuteCandleRevisionConflictException(
    ticker: String,
    bucketStart: Instant,
    revision: Int,
) : IllegalStateException(
    "Minute candle revision conflict: ticker=$ticker, bucketStart=$bucketStart, revision=$revision",
)
