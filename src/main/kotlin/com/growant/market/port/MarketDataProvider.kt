package com.growant.market.port

import java.math.BigDecimal

/**
 * 시세 소스 포트(교체 지점).
 * 현재 구현: SimulatedMarketDataProvider (sim). 추후: KisMarketDataProvider (kis).
 */
interface MarketDataProvider : QuoteReader, TradeTickSource

interface QuoteReader {
    fun currentPrice(ticker: String): BigDecimal
}

interface TradeTickSource {
    fun subscribe(ticker: String, onTick: (Tick) -> Unit): Subscription
}

fun interface Subscription : AutoCloseable {
    override fun close()
}

data class Tick(
    val ticker: String,
    val price: BigDecimal,
    val changeRate: Double,
    val epochMillis: Long,
    val quantity: Long,
    val sequence: Long,
)
