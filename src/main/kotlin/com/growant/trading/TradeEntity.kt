package com.growant.trading

import com.growant.trading.dto.TradeDto
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Entity
@Table(name = "trades")
class TradeEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(nullable = false, length = 10)
    val ticker: String,
    @Column(nullable = false, length = 40)
    val name: String,
    @Column(name = "is_buy", nullable = false)
    val isBuy: Boolean,
    @Column(nullable = false)
    val price: Int,
    @Column(nullable = false)
    val qty: Int,
    @Column(nullable = false)
    val amount: Long,
    @Column(name = "executed_at", nullable = false)
    val executedAt: OffsetDateTime = OffsetDateTime.now(),
)

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd HH:mm")
private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** 엔티티→DTO 매핑 단일 정의(스펙 DRY §3-2) — time "MM.dd HH:mm" 포맷은 여기서만. */
fun TradeEntity.toDto() = TradeDto(
    name = name,
    isBuy = isBuy,
    price = price,
    qty = qty,
    amount = amount,
    time = executedAt.atZoneSameInstant(SEOUL).format(TIME_FMT),
)
