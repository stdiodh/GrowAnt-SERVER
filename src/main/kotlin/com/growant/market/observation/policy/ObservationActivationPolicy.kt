package com.growant.market.observation.policy

import com.growant.market.observation.AdjustmentMode
import com.growant.market.observation.CandleInterval
import com.growant.market.observation.CandleTimeConvention
import com.growant.market.observation.CorrectionPolicy
import com.growant.market.observation.EmptyMinutePolicy
import com.growant.market.observation.MarketDataSemantics
import com.growant.market.observation.MarketSession
import com.growant.market.observation.MarketVenue
import com.growant.market.observation.ObservationClockSample
import com.growant.market.observation.ObservationClockSource
import com.growant.market.observation.ObservationExpectedTicker
import com.growant.market.observation.ObservationOrigin
import com.growant.market.observation.ObservationRole
import com.growant.market.observation.ObservationRun
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationTickerSetChecksum
import com.growant.market.observation.ProviderEventIdScope
import com.growant.market.observation.RightsDecision
import com.growant.market.observation.TimestampOrigin
import com.growant.market.observation.VolumeUnit
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class ObservationActivationPolicy(
    private val clock: Clock,
    private val clockHealthPolicy: ClockHealthPolicy,
) {
    fun assess(
        run: ObservationRun,
        semantics: MarketDataSemantics?,
        clockSample: ObservationClockSample?,
        expectedTickers: List<ObservationExpectedTicker>,
        evaluatedAt: Instant = clock.instant(),
    ): ObservationActivationAssessment {
        val failures = linkedSetOf<ObservationActivationFailure>()

        if (run.state != ObservationRunState.PLANNED) {
            failures += ObservationActivationFailure.RUN_NOT_PLANNED
        }
        if (!run.retentionUntil.isAfter(evaluatedAt)) {
            failures += ObservationActivationFailure.RETENTION_EXPIRED
        }
        if (!run.windowEnd.isAfter(evaluatedAt)) {
            failures += ObservationActivationFailure.OBSERVATION_WINDOW_EXPIRED
        }
        if (evaluatedAt.isAfter(run.windowStart)) {
            failures += ObservationActivationFailure.OBSERVATION_WINDOW_ALREADY_STARTED
        }
        if (run.sourceTreeDirty) {
            failures += ObservationActivationFailure.SOURCE_TREE_DIRTY
        }

        val rights = run.rights
        val rightsDecisions = listOf(
            rights.storage,
            rights.benchmark,
            rights.replay,
            rights.ci,
            rights.internalDisplay,
            rights.externalDistribution,
        )
        if (rightsDecisions.any { it == RightsDecision.UNKNOWN }) {
            failures += ObservationActivationFailure.RIGHTS_UNKNOWN
        }
        if (rights.storage != RightsDecision.ALLOWED) {
            failures += ObservationActivationFailure.STORAGE_NOT_ALLOWED
        }
        if (rights.benchmark != RightsDecision.ALLOWED) {
            failures += ObservationActivationFailure.BENCHMARK_NOT_ALLOWED
        }

        assessExpectedTickers(run, expectedTickers, failures)
        assessSemantics(run, semantics, evaluatedAt, failures)
        collectClockFailures(run, clockSample, evaluatedAt, failures)

        return ObservationActivationAssessment(failures.toSet())
    }

    fun requireActivatable(
        run: ObservationRun,
        semantics: MarketDataSemantics?,
        clockSample: ObservationClockSample?,
        expectedTickers: List<ObservationExpectedTicker>,
        evaluatedAt: Instant = clock.instant(),
    ) {
        val assessment = assess(run, semantics, clockSample, expectedTickers, evaluatedAt)
        if (!assessment.allowed) {
            throw ObservationActivationRejectedException(assessment.failures)
        }
    }

    fun assessClock(
        run: ObservationRun,
        clockSample: ObservationClockSample?,
        evaluatedAt: Instant = clock.instant(),
    ): ObservationActivationAssessment {
        val failures = linkedSetOf<ObservationActivationFailure>()
        collectClockFailures(run, clockSample, evaluatedAt, failures)
        return ObservationActivationAssessment(failures)
    }

    private fun assessExpectedTickers(
        run: ObservationRun,
        expectedTickers: List<ObservationExpectedTicker>,
        failures: MutableSet<ObservationActivationFailure>,
    ) {
        if (expectedTickers.isEmpty()) {
            failures += ObservationActivationFailure.EXPECTED_TICKERS_MISSING
        }
        if (expectedTickers.any { it.scope != run.scope }) {
            failures += ObservationActivationFailure.EXPECTED_TICKER_SCOPE_MISMATCH
        }
        if (expectedTickers.size != run.expectedTickerCount) {
            failures += ObservationActivationFailure.EXPECTED_TICKER_COUNT_MISMATCH
        }
        if (expectedTickers.map(ObservationExpectedTicker::ordinal) != expectedTickers.indices.toList()) {
            failures += ObservationActivationFailure.EXPECTED_TICKER_ORDER_INVALID
        }

        val actualChecksum = runCatching {
            ObservationTickerSetChecksum.sha256(expectedTickers.map(ObservationExpectedTicker::ticker))
        }.getOrNull()
        if (actualChecksum != run.tickerSetChecksumSha256) {
            failures += ObservationActivationFailure.EXPECTED_TICKER_CHECKSUM_MISMATCH
        }
    }

    private fun assessSemantics(
        run: ObservationRun,
        semantics: MarketDataSemantics?,
        evaluatedAt: Instant,
        failures: MutableSet<ObservationActivationFailure>,
    ) {
        if (semantics == null) {
            failures += ObservationActivationFailure.SEMANTICS_MISSING
            return
        }
        if (semantics.scope != run.scope) {
            failures += ObservationActivationFailure.SEMANTICS_SCOPE_MISMATCH
        }
        if (
            semantics.venue == MarketVenue.UNKNOWN ||
            semantics.session == MarketSession.UNKNOWN ||
            semantics.interval == CandleInterval.UNKNOWN ||
            semantics.timestampOrigin == TimestampOrigin.UNKNOWN ||
            semantics.candleTimeConvention == CandleTimeConvention.UNKNOWN ||
            semantics.adjustmentMode == AdjustmentMode.UNKNOWN ||
            semantics.correctionPolicy == CorrectionPolicy.UNKNOWN ||
            semantics.emptyMinutePolicy == EmptyMinutePolicy.UNKNOWN ||
            semantics.volumeUnit == VolumeUnit.UNKNOWN ||
            semantics.providerEventIdScope == ProviderEventIdScope.UNKNOWN
        ) {
            failures += ObservationActivationFailure.SEMANTICS_UNKNOWN
        }
        if (semantics.timestampPrecisionMicros == null || semantics.providerZoneId == null) {
            failures += ObservationActivationFailure.TIMESTAMP_MEANING_INCOMPLETE
        }
        if (semantics.confirmedAt == null || semantics.confirmedAt.isAfter(evaluatedAt)) {
            failures += ObservationActivationFailure.SEMANTICS_UNCONFIRMED
        }

        when (run.role) {
            ObservationRole.REALTIME_POC,
            ObservationRole.KOSPI_FEED,
            -> if (semantics.timestampOrigin != TimestampOrigin.PROVIDER_EVENT) {
                failures += ObservationActivationFailure.PROVIDER_EVENT_TIME_REQUIRED
            }

            ObservationRole.CANDLE_REFERENCE -> {
                if (semantics.timestampOrigin != TimestampOrigin.PROVIDER_CANDLE) {
                    failures += ObservationActivationFailure.PROVIDER_CANDLE_TIME_REQUIRED
                }
                if (
                    semantics.candleTimeConvention == CandleTimeConvention.UNKNOWN ||
                    semantics.candleTimeConvention == CandleTimeConvention.NOT_APPLICABLE
                ) {
                    failures += ObservationActivationFailure.CANDLE_TIME_CONVENTION_REQUIRED
                }
                if (
                    semantics.adjustmentMode == AdjustmentMode.UNKNOWN ||
                    semantics.adjustmentMode == AdjustmentMode.NOT_APPLICABLE
                ) {
                    failures += ObservationActivationFailure.ADJUSTMENT_MODE_REQUIRED
                }
            }
        }
    }

    private fun collectClockFailures(
        run: ObservationRun,
        clockSample: ObservationClockSample?,
        evaluatedAt: Instant,
        failures: MutableSet<ObservationActivationFailure>,
    ) {
        if (clockSample == null) {
            failures += ObservationActivationFailure.CLOCK_SAMPLE_MISSING
            return
        }
        if (clockSample.scope != run.scope) {
            failures += ObservationActivationFailure.CLOCK_SCOPE_MISMATCH
        }
        if (run.latestClockSampleSequence != clockSample.sampleSequence) {
            failures += ObservationActivationFailure.CLOCK_SAMPLE_NOT_LATEST
        }
        if (!clockSample.synchronized) {
            failures += ObservationActivationFailure.CLOCK_NOT_SYNCHRONIZED
        }
        if (
            run.origin == ObservationOrigin.PROVIDER &&
            clockSample.source in setOf(ObservationClockSource.SYSTEM, ObservationClockSource.SYNTHETIC)
        ) {
            failures += ObservationActivationFailure.TRUSTED_CLOCK_SOURCE_REQUIRED
        }

        val clockAssessment = clockHealthPolicy.assess(
            sampledAt = clockSample.sampledAt,
            offset = clockSample.localClockOffsetMicros.asDuration(),
            uncertainty = clockSample.uncertaintyMicros.asDuration(),
            evaluatedAt = evaluatedAt,
        )
        if (!clockAssessment.healthy) {
            failures += ObservationActivationFailure.CLOCK_GATE_FAILED
        }
    }

    private fun Long.asDuration(): Duration = Duration.of(this, ChronoUnit.MICROS)
}

data class ObservationActivationAssessment(
    val failures: Set<ObservationActivationFailure>,
) {
    val allowed: Boolean = failures.isEmpty()
}

enum class ObservationActivationFailure {
    RUN_NOT_PLANNED,
    RETENTION_EXPIRED,
    OBSERVATION_WINDOW_EXPIRED,
    OBSERVATION_WINDOW_ALREADY_STARTED,
    SOURCE_TREE_DIRTY,
    RIGHTS_UNKNOWN,
    STORAGE_NOT_ALLOWED,
    BENCHMARK_NOT_ALLOWED,
    EXPECTED_TICKERS_MISSING,
    EXPECTED_TICKER_SCOPE_MISMATCH,
    EXPECTED_TICKER_COUNT_MISMATCH,
    EXPECTED_TICKER_ORDER_INVALID,
    EXPECTED_TICKER_CHECKSUM_MISMATCH,
    SEMANTICS_MISSING,
    SEMANTICS_SCOPE_MISMATCH,
    SEMANTICS_UNKNOWN,
    SEMANTICS_UNCONFIRMED,
    TIMESTAMP_MEANING_INCOMPLETE,
    PROVIDER_EVENT_TIME_REQUIRED,
    PROVIDER_CANDLE_TIME_REQUIRED,
    CANDLE_TIME_CONVENTION_REQUIRED,
    ADJUSTMENT_MODE_REQUIRED,
    CLOCK_SAMPLE_MISSING,
    CLOCK_SCOPE_MISMATCH,
    CLOCK_SAMPLE_NOT_LATEST,
    CLOCK_NOT_SYNCHRONIZED,
    TRUSTED_CLOCK_SOURCE_REQUIRED,
    CLOCK_GATE_FAILED,
}

class ObservationActivationRejectedException(
    val failures: Set<ObservationActivationFailure>,
) : IllegalStateException(
    "Observation run activation rejected: ${failures.map(Enum<*>::name).sorted().joinToString(",")}",
)
