package com.growant.market.observation.policy

import java.time.Clock
import java.time.Duration
import java.time.Instant

class ClockHealthPolicy(
    private val clock: Clock,
    private val maximumSampleAge: Duration = ObservationClockLimits.maximumSampleAge,
) {
    init {
        require(!maximumSampleAge.isNegative && !maximumSampleAge.isZero) {
            "maximumSampleAge must be positive"
        }
    }

    fun assess(
        sampledAt: Instant,
        offset: Duration,
        uncertainty: Duration,
        evaluatedAt: Instant = clock.instant(),
    ): ClockHealthAssessment {
        if (uncertainty.isNegative) {
            return ClockHealthAssessment.unhealthy(ClockHealthFailure.NEGATIVE_UNCERTAINTY)
        }

        if (sampledAt.isAfter(evaluatedAt)) {
            return ClockHealthAssessment.unhealthy(ClockHealthFailure.FUTURE_SAMPLE)
        }

        val sampleAge = Duration.between(sampledAt, evaluatedAt)
        if (sampleAge > maximumSampleAge) {
            return ClockHealthAssessment.unhealthy(
                failure = ClockHealthFailure.STALE_SAMPLE,
                sampleAge = sampleAge,
            )
        }

        val worstCaseError = runCatching { offset.abs().plus(uncertainty) }
            .getOrElse {
                return ClockHealthAssessment.unhealthy(
                    failure = ClockHealthFailure.ERROR_BUDGET_EXCEEDED,
                    sampleAge = sampleAge,
                )
            }

        if (worstCaseError > ObservationClockLimits.maximumWorstCaseError) {
            return ClockHealthAssessment.unhealthy(
                failure = ClockHealthFailure.ERROR_BUDGET_EXCEEDED,
                sampleAge = sampleAge,
                worstCaseError = worstCaseError,
            )
        }

        return ClockHealthAssessment(
            healthy = true,
            failure = null,
            sampleAge = sampleAge,
            worstCaseError = worstCaseError,
        )
    }

    fun requireHealthy(
        sampledAt: Instant,
        offset: Duration,
        uncertainty: Duration,
        evaluatedAt: Instant = clock.instant(),
    ): ClockHealthAssessment = assess(sampledAt, offset, uncertainty, evaluatedAt).also { assessment ->
        if (!assessment.healthy) {
            throw UnhealthyObservationClockException(assessment.failure!!)
        }
    }
}

object ObservationClockLimits {
    val maximumSampleAge: Duration = Duration.ofMinutes(1)
    val maximumWorstCaseError: Duration = Duration.ofMillis(100)
}

data class ClockHealthAssessment(
    val healthy: Boolean,
    val failure: ClockHealthFailure?,
    val sampleAge: Duration?,
    val worstCaseError: Duration?,
) {
    init {
        require(healthy == (failure == null)) {
            "healthy clock assessments must not have a failure"
        }
    }

    companion object {
        fun unhealthy(
            failure: ClockHealthFailure,
            sampleAge: Duration? = null,
            worstCaseError: Duration? = null,
        ) = ClockHealthAssessment(
            healthy = false,
            failure = failure,
            sampleAge = sampleAge,
            worstCaseError = worstCaseError,
        )
    }
}

enum class ClockHealthFailure {
    NEGATIVE_UNCERTAINTY,
    FUTURE_SAMPLE,
    STALE_SAMPLE,
    ERROR_BUDGET_EXCEEDED,
}

class UnhealthyObservationClockException(
    val failure: ClockHealthFailure,
) : IllegalStateException("Observation clock gate failed: $failure")
