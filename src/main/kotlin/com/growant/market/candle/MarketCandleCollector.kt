package com.growant.market.candle

import com.growant.market.MarketService
import com.growant.market.port.MarketDataProvider
import jakarta.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(name = ["market.candles.collection-enabled"], havingValue = "true")
class MarketCandleCollector(
    private val provider: MarketDataProvider,
    private val marketService: MarketService,
    private val ingestionService: MinuteCandleIngestionService,
) {
    private val subscribedTickers = mutableListOf<String>()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (subscribedTickers.isNotEmpty()) return

        marketService.getMarket().take(TRACKED_TICKER_COUNT).forEach { row ->
            subscribedTickers += row.ticker
            provider.subscribe(row.ticker) { tick ->
                val occurredAt = Instant.ofEpochMilli(tick.epochMillis)
                ingestionService.accept(
                    TradeTick(
                        ticker = tick.ticker,
                        price = tick.price.intValueExact(),
                        quantity = tick.quantity,
                        occurredAt = occurredAt,
                        sequence = tick.sequence,
                    ),
                )
            }
        }
    }

    @Scheduled(fixedDelay = 1_000)
    fun finalizeCompletedCandles() {
        ingestionService.finalizeBefore(Instant.now().minusSeconds(FINALIZATION_DELAY_SECONDS))
    }

    @PreDestroy
    fun stop() {
        subscribedTickers.forEach(provider::unsubscribe)
        subscribedTickers.clear()
    }

    private companion object {
        const val TRACKED_TICKER_COUNT = 5
        const val FINALIZATION_DELAY_SECONDS = 5L
    }
}
