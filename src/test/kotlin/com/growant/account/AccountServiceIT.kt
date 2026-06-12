package com.growant.account

import com.growant.auth.UserEntity
import com.growant.auth.UserJpaRepository
import com.growant.common.INITIAL_CASH
import com.growant.support.PostgresIntegrationTest
import com.growant.trading.TradingService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class AccountServiceIT(
    @Autowired val service: AccountService,
    @Autowired val trading: TradingService,
    @Autowired val users: UserJpaRepository,
) : PostgresIntegrationTest() {

    @Test
    fun `가입 직후 요약 - 1,000만 - 0_0 퍼센트`() {
        val u = users.save(UserEntity(provider = "kakao", nickname = "잇-요약신규", cash = INITIAL_CASH)).id
        val s = service.getSummary(u)
        assertThat(s.totalAsset).isEqualTo(INITIAL_CASH)
        assertThat(s.returnRate).isEqualTo(0.0)
    }

    @Test
    fun `매수 직후에도 총자산 불변 - 현금이 평가로 이동했을 뿐`() {
        val u = users.save(UserEntity(provider = "kakao", nickname = "잇-요약매수", cash = INITIAL_CASH)).id
        trading.placeOrder(u, "005930", true, 3)
        val s = service.getSummary(u)
        assertThat(s.totalAsset).isEqualTo(INITIAL_CASH)
        assertThat(s.returnRate).isEqualTo(0.0)
    }
}
