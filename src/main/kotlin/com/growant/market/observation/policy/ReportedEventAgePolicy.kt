package com.growant.market.observation.policy

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Bounds the age of a provider-reported event at local socket receipt time.
 *
 * The clock offset convention is `local clock - trusted reference clock`.
 * The result corrects the local clock and includes local NTP uncertainty plus
 * one full provider timestamp precision unit. It does not claim to measure
 * exchange-to-provider network latency or correct an unknown provider clock.
 */
class ReportedEventAgePolicy {
    fun calculate(
        reportedEventAt: Instant,
        localSocketReceivedAt: Instant,
        timestampPrecisionMicros: Long,
        localClockOffsetMicros: Long,
        localClockUncertaintyMicros: Long,
    ): ReportedEventAgeBounds {
        require(timestampPrecisionMicros > 0) { "timestampPrecisionMicros must be positive" }
        require(localClockUncertaintyMicros >= 0) {
            "localClockUncertaintyMicros must not be negative"
        }

        val localReportedAgeMicros = ChronoUnit.MICROS.between(reportedEventAt, localSocketReceivedAt)
        val correctedAgeMicros = Math.subtractExact(localReportedAgeMicros, localClockOffsetMicros)
        val errorBudgetMicros = Math.addExact(timestampPrecisionMicros, localClockUncertaintyMicros)
        val lowerCandidate = Math.subtractExact(correctedAgeMicros, errorBudgetMicros)
        val upperCandidate = Math.addExact(correctedAgeMicros, errorBudgetMicros)

        if (upperCandidate < 0) {
            throw ProviderTimestampAheadException()
        }

        return ReportedEventAgeBounds(
            lowerBound = Duration.of(maxOf(0, lowerCandidate), ChronoUnit.MICROS),
            upperBound = Duration.of(maxOf(0, upperCandidate), ChronoUnit.MICROS),
            correctedPointEstimate = Duration.of(maxOf(0, correctedAgeMicros), ChronoUnit.MICROS),
            errorBudget = Duration.of(errorBudgetMicros, ChronoUnit.MICROS),
        )
    }
}

data class ReportedEventAgeBounds(
    val lowerBound: Duration,
    val upperBound: Duration,
    val correctedPointEstimate: Duration,
    val errorBudget: Duration,
)

class ProviderTimestampAheadException : IllegalArgumentException(
    "Provider timestamp is ahead of local receipt beyond the measurement error budget",
)
