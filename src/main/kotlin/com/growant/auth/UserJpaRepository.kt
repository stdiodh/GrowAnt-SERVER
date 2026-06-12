package com.growant.auth

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserJpaRepository : JpaRepository<UserEntity, Long> {
    fun findByProviderAndNickname(provider: String, nickname: String): UserEntity?

    /** 주문 트랜잭션의 직렬화 지점 — 사용자 행 비관적 잠금(스펙 §6.1). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    fun findForUpdate(@Param("id") id: Long): UserEntity?
}
