package com.growant.market.observation.policy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ReportedEventAgePolicyTest {
    private val policy = ReportedEventAgePolicy()

    @Test
    fun `corrects signed local clock offset and returns conservative age bounds`() {
        val bounds = policy.calculate(
            reportedEventAt = Instant.parse("2026-08-15T00:00:00Z"),
            localSocketReceivedAt = Instant.parse("2026-08-15T00:00:00.500Z"),
            timestampPrecisionMicros = 1_000,
            localClockOffsetMicros = 20_000,
            localClockUncertaintyMicros = 10_000,
        )

        assertThat(bounds.correctedPointEstimate).isEqualTo(Duration.ofMillis(480))
        assertThat(bounds.errorBudget).isEqualTo(Duration.ofMillis(11))
        assertThat(bounds.lowerBound).isEqualTo(Duration.ofMillis(469))
        assertThat(bounds.upperBound).isEqualTo(Duration.ofMillis(491))
    }

    @Test
    fun `clamps a lower bound within timestamp precision to zero`() {
        val bounds = policy.calculate(
            reportedEventAt = Instant.parse("2026-08-15T00:00:00Z"),
            localSocketReceivedAt = Instant.parse("2026-08-15T00:00:00.000500Z"),
            timestampPrecisionMicros = 1_000,
            localClockOffsetMicros = 0,
            localClockUncertaintyMicros = 100,
        )

        assertThat(bounds.lowerBound).isEqualTo(Duration.ZERO)
        assertThat(bounds.upperBound).isEqualTo(Duration.ofNanos(1_600_000))
    }

    @Test
    fun `rejects a provider timestamp ahead beyond all known measurement error`() {
        assertThatThrownBy {
            policy.calculate(
                reportedEventAt = Instant.parse("2026-08-15T00:00:01Z"),
                localSocketReceivedAt = Instant.parse("2026-08-15T00:00:00Z"),
                timestampPrecisionMicros = 1_000,
                localClockOffsetMicros = 0,
                localClockUncertaintyMicros = 10_000,
            )
        }.isInstanceOf(ProviderTimestampAheadException::class.java)
    }
}
