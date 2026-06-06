package com.growant.market.port

import java.math.BigDecimal

/**
 * 시세 소스 포트(교체 지점).
 * 현재 구현: SimulatedMarketDataProvider (sim). 추후: KisMarketDataProvider (kis).
 */
interface MarketDataProvider {
    fun currentPrice(ticker: String): BigDecimal
    fun subscribe(ticker: String, onTick: (Tick) -> Unit)
    fun unsubscribe(ticker: String)
}

data class Tick(
    val ticker: String,
    val price: BigDecimal,
    val changeRate: Double,
    val epochMillis: Long,
)
