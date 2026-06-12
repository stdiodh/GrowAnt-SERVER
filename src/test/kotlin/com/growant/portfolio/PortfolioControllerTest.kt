package com.growant.portfolio

import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import com.growant.portfolio.dto.HoldingDto
import com.growant.portfolio.dto.PortfolioDto
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(PortfolioController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class)
class PortfolioControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var service: PortfolioService

    @Test
    fun `GET portfolio me returns envelope for the jwt user`() {
        given(service.getPortfolio(PortfolioOwner.ME, 1L)).willReturn(
            PortfolioDto(
                5.2, 142_600L, 2_739_200L, 2_881_800L,
                listOf(HoldingDto("005930", "삼성전자", 12, 70_000, 76_300)),
            ),
        )
        mockMvc.get("/api/portfolio/me") { with(jwt().jwt { it.subject("1") }) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.returnRate") { value(5.2) } }
            .andExpect { jsonPath("$.data.holdings[0].ticker") { value("005930") } }
    }

    @Test
    fun `GET portfolio ai returns envelope`() {
        given(service.getPortfolio(PortfolioOwner.AI, 1L)).willReturn(
            PortfolioDto(3.8, 117_200L, 3_086_200L, 3_203_400L, emptyList()),
        )
        mockMvc.get("/api/portfolio/ai") { with(jwt().jwt { it.subject("1") }) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.data.returnRate") { value(3.8) } }
    }
}
