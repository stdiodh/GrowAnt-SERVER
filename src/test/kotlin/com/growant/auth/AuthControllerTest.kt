package com.growant.auth

import com.growant.common.config.ApiAuthEntryPoint
import com.growant.common.config.JwtConfig
import com.growant.common.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(AuthController::class)
@Import(SecurityConfig::class, JwtConfig::class, ApiAuthEntryPoint::class, AuthService::class, UserStore::class)
class AuthControllerTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `POST login returns token and user envelope`() {
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"provider":"kakao","nickname":"개미왕"}"""
        }.andExpect { status { isOk() } }
            .andExpect { jsonPath("$.success") { value(true) } }
            .andExpect { jsonPath("$.data.token") { isString() } }
            .andExpect { jsonPath("$.data.user.nickname") { value("개미왕") } }
            .andExpect { jsonPath("$.data.user.provider") { value("kakao") } }
    }

    @Test
    fun `POST login with unknown provider returns 400 INVALID_LOGIN envelope`() {
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"provider":"github","nickname":"개미왕"}"""
        }.andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.success") { value(false) } }
            .andExpect { jsonPath("$.error.code") { value("INVALID_LOGIN") } }
            .andExpect { jsonPath("$.error.eventType") { value("VALIDATION_ERROR") } }
    }

    @Test
    fun `GET me returns user from jwt claims`() {
        mockMvc.get("/api/auth/me") {
            with(jwt().jwt { it.subject("7").claim("nickname", "개미왕").claim("provider", "kakao") })
        }.andExpect { status { isOk() } }
            .andExpect { jsonPath("$.data.id") { value(7) } }
            .andExpect { jsonPath("$.data.nickname") { value("개미왕") } }
            .andExpect { jsonPath("$.data.provider") { value("kakao") } }
    }

    @Test
    fun `GET me without token returns 401 UNAUTHENTICATED envelope`() {
        mockMvc.get("/api/auth/me")
            .andExpect { status { isUnauthorized() } }
            .andExpect { jsonPath("$.success") { value(false) } }
            .andExpect { jsonPath("$.error.code") { value("UNAUTHENTICATED") } }
            .andExpect { jsonPath("$.error.eventType") { value("AUTH_ERROR") } }
            .andExpect { jsonPath("$.error.retryable") { value(false) } }
    }
}
