package com.growant.account

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountServiceTest {
    private val service = AccountService()

    @Test
    fun `summary returns deterministic total asset and return rate`() {
        val s = service.getSummary()
        assertThat(s.totalAsset).isEqualTo(10_520_000L)
        assertThat(s.returnRate).isEqualTo(5.2)
    }
}
