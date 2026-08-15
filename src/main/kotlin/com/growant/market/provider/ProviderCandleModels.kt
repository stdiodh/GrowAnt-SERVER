package com.growant.market.provider

import java.math.BigDecimal

/** 공급자가 명시적으로 반환한 OHLCV만 보존한다. */
data class ProviderOhlcv(
    val openPrice: BigDecimal,
    val highPrice: BigDecimal,
    val lowPrice: BigDecimal,
    val closePrice: BigDecimal,
    val volume: BigDecimal,
)

internal fun ProviderOhlcv.hasSameNumericValues(other: ProviderOhlcv): Boolean =
    openPrice.compareTo(other.openPrice) == 0 &&
        highPrice.compareTo(other.highPrice) == 0 &&
        lowPrice.compareTo(other.lowPrice) == 0 &&
        closePrice.compareTo(other.closePrice) == 0 &&
        volume.compareTo(other.volume) == 0
