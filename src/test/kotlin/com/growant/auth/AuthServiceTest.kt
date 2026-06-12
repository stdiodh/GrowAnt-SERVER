package com.growant.auth

import com.growant.common.config.JwtConfig
import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class AuthServiceTest {
    private val jwtConfig = JwtConfig("test-secret-must-be-32-bytes-min!!")
    private val service = AuthService(UserStore(), jwtConfig.jwtEncoder())
    private val decoder = jwtConfig.jwtDecoder()

    @Test
    fun `같은 provider+nickname은 같은 사용자(멱등), 다른 provider는 다른 사용자`() {
        val a = service.login("kakao", "개미왕")
        val b = service.login("kakao", "개미왕")
        val c = service.login("naver", "개미왕")
        assertThat(b.user.id).isEqualTo(a.user.id)
        assertThat(c.user.id).isNotEqualTo(a.user.id)
    }

    @Test
    fun `발급 토큰은 디코딩되고 sub·nickname·provider 클레임을 담는다`() {
        val res = service.login("google", "  grow  ") // trim 검증 겸용
        assertThat(res.user.nickname).isEqualTo("grow")
        val jwt = decoder.decode(res.token)
        assertThat(jwt.subject).isEqualTo(res.user.id.toString())
        assertThat(jwt.getClaimAsString("nickname")).isEqualTo("grow")
        assertThat(jwt.getClaimAsString("provider")).isEqualTo("google")
        assertThat(jwt.expiresAt).isAfter(Instant.now())
    }

    @Test
    fun `잘못된 로그인 3종 - 불허 provider, 공백 닉네임, 21자 닉네임`() {
        listOf<() -> Unit>(
            { service.login("github", "grow") },
            { service.login("kakao", "   ") },
            { service.login("kakao", "가".repeat(21)) },
        ).forEach { call ->
            assertThatThrownBy(call)
                .isInstanceOf(BusinessException::class.java)
                .satisfies({ ex -> assertThat((ex as BusinessException).code).isEqualTo(ErrorCode.INVALID_LOGIN) })
        }

        // 경계: 정확히 20자는 통과해야 한다(> vs >= 회귀 가드)
        assertThat(service.login("kakao", "가".repeat(20)).user.nickname).hasSize(20)
    }
}
