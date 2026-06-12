package com.growant.auth

import com.growant.common.INITIAL_CASH
import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.support.PostgresIntegrationTest
import com.growant.trading.PositionJpaRepository
import com.growant.trading.TradeJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.jwt.JwtDecoder

class AuthServiceIT(
    @Autowired val service: AuthService,
    @Autowired val users: UserJpaRepository,
    @Autowired val positions: PositionJpaRepository,
    @Autowired val trades: TradeJpaRepository,
    @Autowired val decoder: JwtDecoder,
) : PostgresIntegrationTest() {

    @Test
    fun `가입 시 INITIAL_CASH 지급 - 빈 포트폴리오·빈 내역으로 시작`() {
        val res = service.login("kakao", "잇-신규")
        val u = users.findById(res.user.id).orElseThrow()
        assertThat(u.cash).isEqualTo(INITIAL_CASH)
        assertThat(positions.findByUserId(u.id)).isEmpty()
        assertThat(trades.findByUserIdOrderByExecutedAtDescIdDesc(u.id)).isEmpty()
    }

    @Test
    fun `같은 provider+nickname 재로그인은 같은 사용자(멱등)`() {
        val a = service.login("naver", "잇-멱등")
        val b = service.login("naver", "잇-멱등")
        assertThat(b.user.id).isEqualTo(a.user.id)
    }

    @Test
    fun `발급 토큰 클레임 - sub는 DB id, nickname은 trim 적용`() {
        val res = service.login("google", "  잇-클레임  ")
        val jwt = decoder.decode(res.token)
        assertThat(jwt.subject).isEqualTo(res.user.id.toString())
        assertThat(jwt.getClaimAsString("nickname")).isEqualTo("잇-클레임")
        assertThat(jwt.getClaimAsString("provider")).isEqualTo("google")
    }

    @Test
    fun `잘못된 로그인 3종 거부 + 20자 경계 통과`() {
        listOf(
            { service.login("github", "잇-검증") },
            { service.login("kakao", "   ") },
            { service.login("kakao", "가".repeat(21)) },
        ).forEach { call ->
            assertThatThrownBy { call() }
                .isInstanceOf(BusinessException::class.java)
                .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.INVALID_LOGIN) })
        }
        assertThat(service.login("kakao", "잇".repeat(20)).user.nickname).hasSize(20)
    }
}
