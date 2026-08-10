package com.growant.market.sim

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random

class SimulatedMarketDataProviderTest {
    @Test
    fun `same random seed produces the same price sequence`() {
        val first = SimulatedMarketDataProvider(Random(20260810))
        val second = SimulatedMarketDataProvider(Random(20260810))

        try {
            val firstPrices = List(100) { first.currentPrice("005930") }
            val secondPrices = List(100) { second.currentPrice("005930") }

            assertThat(firstPrices).containsExactlyElementsOf(secondPrices)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `each update stays positive and moves by at most two price ticks`() {
        val provider = SimulatedMarketDataProvider(Random(42))

        try {
            var previous = provider.currentPrice("005930")

            repeat(1_000) {
                val next = provider.currentPrice("005930")
                val absoluteMovement = next.subtract(previous).abs()
                val largestAdjacentUnit = provider.priceUnit(previous).max(provider.priceUnit(next))

                assertThat(next).isPositive()
                assertThat(absoluteMovement).isLessThanOrEqualTo(largestAdjacentUnit.multiply(BigDecimal(2)))
                assertThat(next.remainder(provider.priceUnit(next))).isZero()
                previous = next
            }
        } finally {
            provider.close()
        }
    }

    @Test
    fun `two upward ticks cross a price-unit boundary without creating an invalid price`() {
        val provider = SimulatedMarketDataProvider(Random(1))

        try {
            val next = provider.moveByTicks(BigDecimal(49_950), 2)

            assertThat(next).isEqualByComparingTo(BigDecimal(50_100))
            assertThat(next.remainder(provider.priceUnit(next))).isZero()
        } finally {
            provider.close()
        }
    }

    @Test
    fun `downward ticks use the lower price-unit at a boundary and stay positive`() {
        val provider = SimulatedMarketDataProvider(Random(1))

        try {
            assertThat(provider.moveByTicks(BigDecimal(50_000), -1))
                .isEqualByComparingTo(BigDecimal(49_950))
            assertThat(provider.moveByTicks(BigDecimal(50_000), -2))
                .isEqualByComparingTo(BigDecimal(49_900))
            assertThat(provider.moveByTicks(BigDecimal.ONE, -2))
                .isEqualByComparingTo(BigDecimal.ONE)
        } finally {
            provider.close()
        }
    }
}
