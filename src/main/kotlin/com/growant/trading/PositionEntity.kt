package com.growant.trading

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "positions")
class PositionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(nullable = false, length = 10)
    val ticker: String,
    @Column(nullable = false)
    var qty: Int,
    @Column(name = "avg_price", nullable = false)
    var avgPrice: Int,
)

/** 엔티티→도메인 매핑 단일 정의(스펙 DRY §3-2) — 서비스에서 직접 매핑 금지. */
fun PositionEntity.toDomain() = Position(ticker, qty, avgPrice)
