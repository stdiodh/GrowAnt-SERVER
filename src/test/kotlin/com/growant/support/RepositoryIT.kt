package com.growant.support

import com.growant.auth.UserEntity
import com.growant.auth.UserJpaRepository
import com.growant.trading.PositionEntity
import com.growant.trading.PositionJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException

class RepositoryIT(
    @Autowired val users: UserJpaRepository,
    @Autowired val positions: PositionJpaRepository,
) : PostgresIntegrationTest() {

    @Test
    fun `Flyway 스키마에 사용자 저장·조회가 동작한다`() {
        val saved = users.save(UserEntity(provider = "kakao", nickname = "스모크-유저", cash = 10_000_000L))
        assertThat(saved.id).isPositive()
        assertThat(users.findByProviderAndNickname("kakao", "스모크-유저")!!.id).isEqualTo(saved.id)
    }

    @Test
    fun `같은 provider+nickname 중복 저장은 유니크 제약으로 거부된다`() {
        users.saveAndFlush(UserEntity(provider = "naver", nickname = "중복닉", cash = 0))
        assertThatThrownBy {
            users.saveAndFlush(UserEntity(provider = "naver", nickname = "중복닉", cash = 0))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 사용자 동일 티커 포지션 중복은 유니크 제약으로 거부된다`() {
        val u = users.save(UserEntity(provider = "google", nickname = "포지션닉", cash = 0))
        positions.saveAndFlush(PositionEntity(userId = u.id, ticker = "005930", qty = 1, avgPrice = 1))
        assertThatThrownBy {
            positions.saveAndFlush(PositionEntity(userId = u.id, ticker = "005930", qty = 2, avgPrice = 2))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
