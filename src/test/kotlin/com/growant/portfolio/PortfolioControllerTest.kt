package com.growant.portfolio

import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import com.growant.market.MarketService
import com.growant.trading.TradingService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(PortfolioController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class, PortfolioService::class, MarketService::class, TradingService::class)
class PortfolioControllerTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `GET portfolio me returns envelope with aggregates and 4 holdings`() {
        mockMvc.get("/api/portfolio/me") { with(jwt()) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.returnRate") { value(5.2) } }
            .andExpect { jsonPath("$.data.profit") { value(142600) } }
            .andExpect { jsonPath("$.data.holdings.length()") { value(4) } }
            .andExpect { jsonPath("$.data.holdings[0].ticker") { value("005930") } }
    }

    @Test
    fun `GET portfolio ai returns envelope with aggregates`() {
        mockMvc.get("/api/portfolio/ai") { with(jwt()) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.returnRate") { value(3.8) } }
            .andExpect { jsonPath("$.data.holdings.length()") { value(4) } }
    }
}
