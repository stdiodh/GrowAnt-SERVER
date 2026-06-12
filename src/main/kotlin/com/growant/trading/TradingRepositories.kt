package com.growant.trading

import org.springframework.data.jpa.repository.JpaRepository

interface PositionJpaRepository : JpaRepository<PositionEntity, Long> {
    fun findByUserId(userId: Long): List<PositionEntity>
    fun findByUserIdAndTicker(userId: Long, ticker: String): PositionEntity?
}

interface TradeJpaRepository : JpaRepository<TradeEntity, Long> {
    /** 최신순 — 동일 타임스탬프 동률은 id 역순으로 안정 정렬. */
    fun findByUserIdOrderByExecutedAtDescIdDesc(userId: Long): List<TradeEntity>
}
