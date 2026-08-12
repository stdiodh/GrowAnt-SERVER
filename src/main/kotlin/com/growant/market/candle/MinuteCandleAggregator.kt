package com.growant.market.candle

import java.time.Instant
import java.time.temporal.ChronoUnit

class MinuteCandleAggregator(
    private val source: String = "trade-tick",
) {
    private data class CandleKey(
        val ticker: String,
        val bucketStart: Instant,
    )

    private data class TickOrder(
        val occurredAt: Instant,
        val sequence: Long,
    ) : Comparable<TickOrder> {
        override fun compareTo(other: TickOrder): Int {
            val timeComparison = occurredAt.compareTo(other.occurredAt)
            return if (timeComparison != 0) timeComparison else sequence.compareTo(other.sequence)
        }
    }

    private data class TickIdentity(
        val occurredAt: Instant,
        val sequence: Long,
    )

    private class CandleState(tick: TradeTick) {
        var open = tick.price
        var high = tick.price
        var low = tick.price
        var close = tick.price
        var volume = tick.quantity
        var tradeCount = 1L
        var firstOrder = TickOrder(tick.occurredAt, tick.sequence)
        var lastOrder = firstOrder
        val identities = hashSetOf(tick.identity())

        fun add(tick: TradeTick): Boolean {
            if (!identities.add(tick.identity())) return false

            val order = TickOrder(tick.occurredAt, tick.sequence)
            if (order < firstOrder) {
                firstOrder = order
                open = tick.price
            }
            if (order > lastOrder) {
                lastOrder = order
                close = tick.price
            }
            high = maxOf(high, tick.price)
            low = minOf(low, tick.price)
            volume += tick.quantity
            tradeCount++
            return true
        }

        private fun TradeTick.identity() = TickIdentity(occurredAt, sequence)
    }

    private val states = mutableMapOf<CandleKey, CandleState>()
    private var finalizedBeforeExclusive: Instant = Instant.MIN

    init {
        require(source.isNotBlank()) { "source must not be blank" }
    }

    fun accept(tick: TradeTick): Boolean = acceptTick(tick).accepted

    fun acceptTick(tick: TradeTick): TickAcceptance {
        require(tick.ticker.isNotBlank()) { "ticker must not be blank" }
        require(tick.price > 0) { "price must be positive" }
        require(tick.quantity > 0) { "quantity must be positive" }

        val bucketStart = tick.occurredAt.truncatedTo(ChronoUnit.MINUTES)
        if (!bucketStart.plus(1, ChronoUnit.MINUTES).isAfter(finalizedBeforeExclusive)) {
            return TickAcceptance.TOO_LATE
        }

        val key = CandleKey(ticker = tick.ticker, bucketStart = bucketStart)
        val state = states[key]
        if (state == null) {
            states[key] = CandleState(tick)
            return TickAcceptance.ACCEPTED
        } else {
            return if (state.add(tick)) TickAcceptance.ACCEPTED else TickAcceptance.DUPLICATE
        }
    }

    fun drainFinalized(
        beforeExclusive: Instant,
        beforeRemove: (MinuteCandle) -> Unit = {},
    ): List<MinuteCandle> {
        val finalizedKeys = states.keys
            .filter { key -> !key.bucketStart.plus(1, ChronoUnit.MINUTES).isAfter(beforeExclusive) }
            .sortedWith(keyComparator)

        val finalized = finalizedKeys.map { key ->
            val candle = checkNotNull(states[key]).toCandle(key, isFinal = true)
            beforeRemove(candle)
            states.remove(key)
            candle
        }
        finalizedBeforeExclusive = maxOf(finalizedBeforeExclusive, beforeExclusive)
        return finalized
    }

    fun snapshots(): List<MinuteCandle> = states.entries
        .sortedWith(compareBy({ it.key.bucketStart }, { it.key.ticker }))
        .map { (key, state) -> state.toCandle(key, isFinal = false) }

    private fun CandleState.toCandle(key: CandleKey, isFinal: Boolean) = MinuteCandle(
        ticker = key.ticker,
        bucketStart = key.bucketStart,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        tradeCount = tradeCount,
        isFinal = isFinal,
        source = source,
    )

    private companion object {
        val keyComparator = compareBy<CandleKey>({ it.bucketStart }, { it.ticker })
    }
}
