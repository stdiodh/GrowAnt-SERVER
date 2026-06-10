package com.growant.account.dto

data class AccountSummaryDto(
    val totalAsset: Long,
    val returnRate: Double, // 소수 1자리 반올림
)
