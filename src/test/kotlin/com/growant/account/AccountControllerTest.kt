package com.growant.account

import com.growant.common.config.SecurityConfig
import com.growant.market.MarketService
import com.growant.portfolio.PortfolioService
import com.growant.trading.TradingService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(AccountController::class)
@Import(SecurityConfig::class, AccountService::class, PortfolioService::class, MarketService::class, TradingService::class)
class AccountControllerTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `GET account summary returns envelope`() {
        mockMvc.get("/api/account/summary")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.totalAsset") { value(10520000) } }
            .andExpect { jsonPath("$.data.returnRate") { value(5.2) } }
    }
}
