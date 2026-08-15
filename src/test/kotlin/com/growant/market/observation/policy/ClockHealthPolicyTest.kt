package com.growant.market.observation.policy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ClockHealthPolicyTest {
    private val now = Instant.parse("2026-08-15T00:00:00Z")
    private val policy = ClockHealthPolicy(
        clock = Clock.fixed(now, ZoneOffset.UTC),
        maximumSampleAge = Duration.ofMinutes(5),
    )

    @Test
    fun `accepts a fresh sample at the inclusive one hundred millisecond boundary`() {
        val assessment = policy.assess(
            sampledAt = now.minusSeconds(300),
            offset = Duration.ofMillis(-80),
            uncertainty = Duration.ofMillis(20),
        )

        assertThat(assessment.healthy).isTrue()
        assertThat(assessment.failure).isNull()
        assertThat(assessment.sampleAge).isEqualTo(Duration.ofMinutes(5))
        assertThat(assessment.worstCaseError).isEqualTo(Duration.ofMillis(100))
    }

    @Test
    fun `rejects a clock whose offset and uncertainty exceed the budget`() {
        val assessment = policy.assess(
            sampledAt = now.minusSeconds(1),
            offset = Duration.ofMillis(81),
            uncertainty = Duration.ofMillis(20),
        )

        assertThat(assessment.healthy).isFalse()
        assertThat(assessment.failure).isEqualTo(ClockHealthFailure.ERROR_BUDGET_EXCEEDED)
        assertThat(assessment.worstCaseError).isEqualTo(Duration.ofMillis(101))
    }

    @Test
    fun `rejects stale future and negative uncertainty samples`() {
        assertThat(policy.assess(now.minusSeconds(301), Duration.ZERO, Duration.ZERO).failure)
            .isEqualTo(ClockHealthFailure.STALE_SAMPLE)
        assertThat(policy.assess(now.plusNanos(1), Duration.ZERO, Duration.ZERO).failure)
            .isEqualTo(ClockHealthFailure.FUTURE_SAMPLE)
        assertThat(policy.assess(now, Duration.ZERO, Duration.ofNanos(-1)).failure)
            .isEqualTo(ClockHealthFailure.NEGATIVE_UNCERTAINTY)
    }

    @Test
    fun `require healthy reports the failed gate without payload data`() {
        assertThatThrownBy {
            policy.requireHealthy(
                sampledAt = now.minusSeconds(301),
                offset = Duration.ZERO,
                uncertainty = Duration.ZERO,
            )
        }
            .isInstanceOf(UnhealthyObservationClockException::class.java)
            .hasMessage("Observation clock gate failed: STALE_SAMPLE")
    }
}
