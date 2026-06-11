package com.growant.market

import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(MarketController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class, MarketService::class)
class MarketControllerTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `GET market returns success envelope with 8 rows`() {
        mockMvc.get("/api/market") { with(jwt()) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.length()") { value(8) } }
            .andExpect { jsonPath("$.data[0].ticker") { value("005930") } }
    }

    @Test
    fun `GET market detail returns candles and fundamentals`() {
        mockMvc.get("/api/market/005930") { with(jwt()) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.data.candles.length()") { value(10) } }
            .andExpect { jsonPath("$.data.per") { value(12.4) } }
    }

    @Test
    fun `GET market detail unknown ticker returns INVALID_TICKER 400`() {
        mockMvc.get("/api/market/999999") { with(jwt()) }
            .andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.success") { value(false) } }
            .andExpect { jsonPath("$.error.code") { value("INVALID_TICKER") } }
            .andExpect { jsonPath("$.error.eventType") { value("VALIDATION_ERROR") } }
    }
}
