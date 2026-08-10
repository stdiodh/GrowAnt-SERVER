package com.growant.market.sim

import com.growant.market.port.MarketDataProvider
import com.growant.market.port.Tick
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/**
 * 시뮬레이션 시세 (KIS 보류 동안 사용). 랜덤워크로 현재가를 생성한다.
 * 확장 옵션: 변동성·추세·뉴스 시나리오를 LLM이 생성해 주입.
 */
// NOTE(market-slice): REST 스냅샷(목록/상세)은 이 random-walk currentPrice()를 쓰지 않는다.
//   스냅샷은 MarketService의 결정적 카탈로그로 서빙(목록↔상세 일관). 스펙 §3.2/C4
//   이 provider의 currentPrice()/subscribe()는 로컬 실시간 스트리밍 검증에만 사용한다. 스펙 §10
@Component
@ConditionalOnProperty(name = ["market.provider"], havingValue = "sim", matchIfMissing = true)
class SimulatedMarketDataProvider internal constructor(
    private val random: Random = Random.Default,
) : MarketDataProvider {

    private val last = ConcurrentHashMap<String, BigDecimal>()
    private val subscriptions = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val sequence = AtomicLong()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "simulated-market-data").apply { isDaemon = true }
    }

    private fun seed(ticker: String): BigDecimal =
        last.getOrPut(ticker) {
            val unalignedPrice = BigDecimal(50_000 + random.nextInt(50_000))
            val unit = priceUnit(unalignedPrice)
            unalignedPrice.divide(unit, 0, RoundingMode.HALF_UP).multiply(unit)
        }

    override fun currentPrice(ticker: String): BigDecimal {
        val prev = seed(ticker)
        val movementInTicks = when (random.nextInt(100)) {
            0 -> -2
            in 1..24 -> -1
            in 25..74 -> 0
            in 75..98 -> 1
            else -> 2
        }
        val next = moveByTicks(prev, movementInTicks)
        last[ticker] = next
        return next
    }

    override fun subscribe(ticker: String, onTick: (Tick) -> Unit) {
        subscriptions.computeIfAbsent(ticker) {
            scheduler.scheduleAtFixedRate(
                {
                    try {
                        val price = currentPrice(ticker)
                        onTick(
                            Tick(
                                ticker = ticker,
                                price = price,
                                changeRate = 0.0,
                                epochMillis = System.currentTimeMillis(),
                                quantity = random.nextLong(1, 1_001),
                                sequence = sequence.incrementAndGet(),
                            ),
                        )
                    } catch (exception: Exception) {
                        logger.error("Simulated tick callback failed for ticker={}", ticker, exception)
                    }
                },
                0,
                1,
                TimeUnit.SECONDS,
            )
        }
    }

    override fun unsubscribe(ticker: String) {
        subscriptions.remove(ticker)?.cancel(false)
        last.remove(ticker)
    }

    @PreDestroy
    fun close() {
        subscriptions.values.forEach { it.cancel(false) }
        subscriptions.clear()
        last.clear()
        scheduler.shutdownNow()
    }

    internal fun moveByTicks(price: BigDecimal, movementInTicks: Int): BigDecimal {
        require(price > BigDecimal.ZERO) { "price must be positive" }
        require(movementInTicks in -2..2) { "movementInTicks must be between -2 and 2" }

        val direction = movementInTicks.compareTo(0)
        var next = price
        repeat(abs(movementInTicks)) {
            val unitReference = if (direction < 0) {
                next.subtract(BigDecimal.ONE).max(BigDecimal.ONE)
            } else {
                next
            }
            next = next
                .add(priceUnit(unitReference).multiply(BigDecimal.valueOf(direction.toLong())))
                .max(BigDecimal.ONE)
        }
        return next
    }

    internal fun priceUnit(price: BigDecimal): BigDecimal = when {
        price < BigDecimal(2_000) -> BigDecimal.ONE
        price < BigDecimal(5_000) -> BigDecimal(5)
        price < BigDecimal(20_000) -> BigDecimal.TEN
        price < BigDecimal(50_000) -> BigDecimal(50)
        price < BigDecimal(200_000) -> BigDecimal(100)
        price < BigDecimal(500_000) -> BigDecimal(500)
        else -> BigDecimal(1_000)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SimulatedMarketDataProvider::class.java)
    }
}
