package com.growant.trading

import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.trading.dto.TradeDto
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(TradingController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class)
class TradingControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var service: TradingService

    @Test
    fun `POST orders executes and returns trade envelope`() {
        given(service.placeOrder(1L, "005930", true, 1))
            .willReturn(TradeDto("삼성전자", true, 76_300, 1, 76_300L, "06.12 10:00"))
        mockMvc.post("/api/orders") {
            with(jwt().jwt { it.subject("1") })
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
        given(service.placeOrder(1L, "005380", true, 1000))
            .willThrow(BusinessException(ErrorCode.ORDER_INSUFFICIENT_FUNDS))
        mockMvc.post("/api/orders") {
            with(jwt().jwt { it.subject("1") })
            contentType = MediaType.APPLICATION_JSON
            content = """{"ticker":"005380","isBuy":true,"qty":1000}"""
        }.andExpect { status { isConflict() } }
            .andExpect { jsonPath("$.success") { value(false) } }
            .andExpect { jsonPath("$.error.code") { value("ORDER_INSUFFICIENT_FUNDS") } }
            .andExpect { jsonPath("$.error.eventType") { value("ORDER_ERROR") } }
    }

    @Test
    fun `GET trades returns history envelope for the jwt user`() {
        given(service.getTrades(1L))
            .willReturn(listOf(TradeDto("NAVER", false, 198_400, 1, 198_400L, "06.12 09:00")))
        mockMvc.get("/api/trades") { with(jwt().jwt { it.subject("1") }) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data[0].name") { value("NAVER") } }
    }
}
