package com.growant.trading

import com.growant.auth.UserEntity
import com.growant.auth.UserJpaRepository
import com.growant.common.INITIAL_CASH
import com.growant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConcurrentOrderIT(
    @Autowired val service: TradingService,
    @Autowired val users: UserJpaRepository,
) : PostgresIntegrationTest() {

    @Test
    fun `동시 매수에도 현금이 정확히 차감된다 - 사용자 행 비관적 잠금`() {
        val u = users.save(UserEntity(provider = "kakao", nickname = "잇-동시", cash = INITIAL_CASH)).id
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val futures = (1..10).map {
            pool.submit {
                start.await()
                service.placeOrder(u, "005930", true, 1) // 76,300
            }
        }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(service.getCash(u)).isEqualTo(INITIAL_CASH - 10 * 76_300L)
        assertThat(service.getMePositions(u).single().qty).isEqualTo(10)
        assertThat(service.getTrades(u)).hasSize(10)
    }
}
