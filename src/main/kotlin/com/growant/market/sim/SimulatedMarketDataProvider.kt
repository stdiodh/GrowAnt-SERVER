package com.growant.market.sim

import com.growant.market.port.MarketDataProvider
import com.growant.market.port.Tick
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 시뮬레이션 시세 (KIS 보류 동안 사용). 랜덤워크로 현재가를 생성한다.
 * 확장 옵션: 변동성·추세·뉴스 시나리오를 LLM이 생성해 주입.
 */
@Component
@ConditionalOnProperty(name = ["market.provider"], havingValue = "sim", matchIfMissing = true)
class SimulatedMarketDataProvider : MarketDataProvider {

    private val last = ConcurrentHashMap<String, BigDecimal>()

    private fun seed(ticker: String): BigDecimal =
        last.getOrPut(ticker) { BigDecimal(50_000 + Random.nextInt(50_000)) }

    override fun currentPrice(ticker: String): BigDecimal {
        val prev = seed(ticker)
        val drift = BigDecimal(Random.nextDouble(-0.01, 0.01)).multiply(prev)
        val next = prev.add(drift).max(BigDecimal.ONE).setScale(0, RoundingMode.HALF_UP)
        last[ticker] = next
        return next
    }

    override fun subscribe(ticker: String, onTick: (Tick) -> Unit) {
        // TODO: 스케줄러로 주기적 onTick 발행 → market 서비스에서 Redis pub/sub 으로 브로드캐스트
    }

    override fun unsubscribe(ticker: String) {
        last.remove(ticker)
    }
}
