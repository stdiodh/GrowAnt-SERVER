package com.growant.portfolio

import com.growant.market.MarketService
import com.growant.trading.Position
import com.growant.trading.TradingService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

class PortfolioServiceTest {
    private val market = MarketService()
    private val trading: TradingService = mock(TradingService::class.java)
    private val service = PortfolioService(market, trading)

    private val mePositions = listOf(
        Position("005930", 12, 70_000),
        Position("000660", 4, 185_000),
        Position("035420", 3, 189_000),
        Position("000270", 6, 98_700),
    )

    @Test
    fun `ME portfolio aggregates from trading positions`() {
        given(trading.getMePositions(1L)).willReturn(mePositions)
        val p = service.getPortfolio(PortfolioOwner.ME, 1L)
        assertThat(p.holdings).hasSize(4)
        assertThat(p.cost).isEqualTo(2_739_200L)
        assertThat(p.value).isEqualTo(2_881_800L)
        assertThat(p.profit).isEqualTo(142_600L)
        assertThat(p.returnRate).isEqualTo(5.2)
    }

    @Test
    fun `AI portfolio aggregates to plus 3_8 percent`() {
        val p = service.getPortfolio(PortfolioOwner.AI, 1L)
        assertThat(p.holdings).hasSize(4)
        assertThat(p.cost).isEqualTo(3_086_200L)
        assertThat(p.value).isEqualTo(3_203_400L)
        assertThat(p.profit).isEqualTo(117_200L)
        assertThat(p.returnRate).isEqualTo(3.8)
    }

    @Test
    fun `빈 포지션이면 비용·수익률 0에 빈 보유 목록`() {
        given(trading.getMePositions(2L)).willReturn(emptyList())
        val p = service.getPortfolio(PortfolioOwner.ME, 2L)
        assertThat(p.cost).isEqualTo(0L)
        assertThat(p.returnRate).isEqualTo(0.0)
        assertThat(p.holdings).isEmpty()
    }

    @Test
    fun `current prices and names come from market catalog`() {
        given(trading.getMePositions(1L)).willReturn(mePositions)
        val catalog = MarketService().getMarket().associateBy { it.ticker }
        val all = service.getPortfolio(PortfolioOwner.ME, 1L).holdings +
            service.getPortfolio(PortfolioOwner.AI, 1L).holdings
        all.forEach { h ->
            assertThat(h.currentPrice).isEqualTo(catalog.getValue(h.ticker).price)
            assertThat(h.name).isEqualTo(catalog.getValue(h.ticker).name)
        }
    }
}
