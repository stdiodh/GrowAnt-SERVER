package com.growant.portfolio

import com.growant.market.MarketService
import com.growant.trading.TradingService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioServiceTest {
    private val market = MarketService()
    private val service = PortfolioService(market, TradingService(market))

    @Test
    fun `ME portfolio aggregates to plus 5_2 percent`() {
        val p = service.getPortfolio(PortfolioOwner.ME)
        assertThat(p.holdings).hasSize(4)
        assertThat(p.cost).isEqualTo(2_739_200L)
        assertThat(p.value).isEqualTo(2_881_800L)
        assertThat(p.profit).isEqualTo(142_600L)
        assertThat(p.returnRate).isEqualTo(5.2)
    }

    @Test
    fun `AI portfolio aggregates to plus 3_8 percent`() {
        val p = service.getPortfolio(PortfolioOwner.AI)
        assertThat(p.holdings).hasSize(4)
        assertThat(p.cost).isEqualTo(3_086_200L)
        assertThat(p.value).isEqualTo(3_203_400L)
        assertThat(p.profit).isEqualTo(117_200L)
        assertThat(p.returnRate).isEqualTo(3.8)
    }

    @Test
    fun `current prices and names come from market catalog`() {
        val market = MarketService().getMarket().associateBy { it.ticker }
        val all = service.getPortfolio(PortfolioOwner.ME).holdings +
            service.getPortfolio(PortfolioOwner.AI).holdings
        all.forEach { h ->
            assertThat(h.currentPrice).isEqualTo(market.getValue(h.ticker).price)
            assertThat(h.name).isEqualTo(market.getValue(h.ticker).name)
        }
    }
}
