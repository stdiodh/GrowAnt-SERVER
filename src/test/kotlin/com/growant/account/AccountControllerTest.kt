package com.growant.account

import com.growant.account.dto.AccountSummaryDto
import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(AccountController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class)
class AccountControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var service: AccountService

    @Test
    fun `GET account summary returns envelope for the jwt user`() {
        given(service.getSummary(1L)).willReturn(AccountSummaryDto(10_000_000L, 0.0))
        mockMvc.get("/api/account/summary") { with(jwt().jwt { it.subject("1") }) }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.totalAsset") { value(10000000) } }
            .andExpect { jsonPath("$.data.returnRate") { value(0.0) } }
    }
}
