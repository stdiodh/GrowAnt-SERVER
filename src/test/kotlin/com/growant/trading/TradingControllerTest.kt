// NOTE: @WebMvcTest 컨텍스트가 테스트 간 공유되어 TradingService 상태가 누적되므로 GET 테스트는 정확한 개수 대신 형태만 단언한다.
package com.growant.trading

import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import com.growant.market.MarketService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(TradingController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class, TradingService::class, MarketService::class)
class TradingControllerTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `POST orders executes and returns trade envelope`() {
        mockMvc.post("/api/orders") {
            with(jwt())
            contentType = MediaType.APPLICATION_JSON
            content = """{"ticker":"005930","isBuy":true,"qty":1}"""
        }.andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.name") { value("삼성전자") } }
            .andExpect { jsonPath("$.data.isBuy") { value(true) } }
            .andExpect { jsonPath("$.data.amount") { value(76300) } }
    }

    @Test
    fun `POST orders insufficient funds returns 409 error envelope`() {
        mockMvc.post("/api/orders") {
            with(jwt())
            contentType = MediaType.APPLICATION_JSON
            content = """{"ticker":"005380","isBuy":true,"qty":1000}"""
        }.andExpect { status { isConflict() } }
            .andExpect { jsonPath("$.success") { value(false) } }
            .andExpect { jsonPath("$.error.code") { value("ORDER_INSUFFICIENT_FUNDS") } }
            .andExpect { jsonPath("$.error.eventType") { value("ORDER_ERROR") } }
    }

    @Test
    fun `GET trades returns history envelope`() {
        mockMvc.get("/api/trades") { with(jwt()) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data[0].name") { isString() } }
    }
}
