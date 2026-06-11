package com.growant.account

import com.growant.market.MarketService
import com.growant.portfolio.PortfolioService
import com.growant.trading.TradingService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountServiceTest {
    private val market = MarketService()
    private val trading = TradingService(market)
    private val service = AccountService(trading, PortfolioService(market, trading))

    @Test
    fun `summary returns deterministic total asset and return rate`() {
        val s = service.getSummary()
        assertThat(s.totalAsset).isEqualTo(10_520_000L)
        assertThat(s.returnRate).isEqualTo(5.2)
    }
}
