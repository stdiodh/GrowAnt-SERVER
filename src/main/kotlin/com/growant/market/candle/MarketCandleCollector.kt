package com.growant.market.candle

import com.growant.market.port.InstrumentCatalog
import com.growant.market.port.Subscription
import com.growant.market.port.TradeTickSource
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
@ConditionalOnProperty(name = ["market.candles.collection-enabled"], havingValue = "true")
class MarketCandleCollector(
    private val tickSource: TradeTickSource,
    private val instrumentCatalog: InstrumentCatalog,
    private val ingestionService: MinuteCandleIngestionService,
    private val policy: MinuteCandlePolicy = MinuteCandlePolicy(CandleProperties()),
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class ActiveSubscription(
        val ticker: String,
        val subscription: Subscription,
    )

    private val activeSubscriptions = mutableListOf<ActiveSubscription>()

    @EventListener(ApplicationReadyEvent::class)
    @Synchronized
    fun start() {
        if (activeSubscriptions.isNotEmpty()) return

        val missingTickers = policy.trackedTickers.filterNot(instrumentCatalog::contains)
        check(missingTickers.isEmpty()) { "Tracked candle tickers are missing from the market catalog: $missingTickers" }

        val acquired = mutableListOf<ActiveSubscription>()
        try {
            policy.trackedTickers.forEach { ticker ->
                val subscription = tickSource.subscribe(ticker) onTick@{ tick ->
                    if (tick.ticker != ticker) {
                        logger.warn(
                            "Ignored misrouted tick: subscribed={}, received={}",
                            ticker,
                            tick.ticker,
                        )
                        return@onTick
                    }
                    val occurredAt = Instant.ofEpochMilli(tick.epochMillis)
                    when (ingestionService.acceptTick(
                        TradeTick(
                            ticker = tick.ticker,
                            price = tick.price.intValueExact(),
                            quantity = tick.quantity,
                            occurredAt = occurredAt,
                            sequence = tick.sequence,
                        ),
                    )) {
                        TickAcceptance.ACCEPTED -> Unit
                        TickAcceptance.DUPLICATE -> logger.debug(
                            "Ignored duplicate tick: ticker={}, occurredAt={}, sequence={}",
                            tick.ticker,
                            occurredAt,
                            tick.sequence,
                        )
                        TickAcceptance.TOO_LATE -> logger.warn(
                            "Ignored late tick: ticker={}, occurredAt={}, sequence={}",
                            tick.ticker,
                            occurredAt,
                            tick.sequence,
                        )
                    }
                }
                acquired += ActiveSubscription(ticker, subscription)
            }
            activeSubscriptions += acquired
        } catch (exception: Exception) {
            rollback(acquired, exception)
            throw exception
        }
    }

    @Scheduled(fixedDelayString = "\${market.candles.scheduler-interval:1s}")
    fun finalizeCompletedCandles() {
        ingestionService.finalizeBefore(clock.instant().minus(policy.finalizationDelay))
    }

    @PreDestroy
    @Synchronized
    fun stop() {
        val failed = mutableListOf<ActiveSubscription>()
        activeSubscriptions.asReversed().forEach { active ->
            try {
                active.subscription.close()
            } catch (exception: Exception) {
                failed += active
                logger.warn("Failed to close market tick subscription: ticker={}", active.ticker, exception)
            }
        }
        activeSubscriptions.clear()
        activeSubscriptions += failed.asReversed()
    }

    private fun rollback(acquired: List<ActiveSubscription>, cause: Exception) {
        acquired.asReversed().forEach { active ->
            try {
                active.subscription.close()
            } catch (closeFailure: Exception) {
                cause.addSuppressed(closeFailure)
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(MarketCandleCollector::class.java)
    }
}
