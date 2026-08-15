package com.growant.market.observation.policy

import com.growant.market.observation.AdjustmentMode
import com.growant.market.observation.CandleInterval
import com.growant.market.observation.CandleTimeConvention
import com.growant.market.observation.CorrectionPolicy
import com.growant.market.observation.EmptyMinutePolicy
import com.growant.market.observation.MarketSession
import com.growant.market.observation.MarketVenue
import com.growant.market.observation.ObservationClockSource
import com.growant.market.observation.ObservationExpectedTicker
import com.growant.market.observation.ObservationOrigin
import com.growant.market.observation.ObservationRole
import com.growant.market.observation.ObservationRun
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationTestFixtures
import com.growant.market.observation.ProviderEventIdScope
import com.growant.market.observation.RightsDecision
import com.growant.market.observation.TimestampOrigin
import com.growant.market.observation.VolumeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

class ObservationActivationPolicyTest {
    private val now = ObservationTestFixtures.baseTime
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = ObservationActivationPolicy(
        clock = clock,
        clockHealthPolicy = ClockHealthPolicy(
            clock = clock,
            maximumSampleAge = Duration.ofMinutes(1),
        ),
    )

    @Test
    fun `allows a planned synthetic run with immutable tickers semantics rights and a healthy clock`() {
        val run = activatableRun()

        val assessment = policy.assess(
            run = run,
            semantics = ObservationTestFixtures.semantics(run.scope),
            clockSample = ObservationTestFixtures.clockSample(run.scope),
            expectedTickers = ObservationTestFixtures.expectedTickers(run.scope),
        )

        assertThat(assessment.allowed).isTrue()
        assertThat(assessment.failures).isEmpty()
    }

    @Test
    fun `rejects a non-planned or expired run`() {
        val run = ObservationTestFixtures.run(
            state = ObservationRunState.RUNNING,
            retentionUntil = now,
            createdAt = now.minusSeconds(10),
            windowStart = now.minusSeconds(9),
            windowEnd = now.minusSeconds(1),
            startedAt = now.minusSeconds(1),
        )

        assertThat(policy.assess(run, semantics(run), clockSample(run), tickers(run)).failures)
            .containsExactlyInAnyOrder(
                ObservationActivationFailure.RUN_NOT_PLANNED,
                ObservationActivationFailure.RETENTION_EXPIRED,
                ObservationActivationFailure.OBSERVATION_WINDOW_EXPIRED,
                ObservationActivationFailure.OBSERVATION_WINDOW_ALREADY_STARTED,
            )
    }

    @Test
    fun `rejects activation after the planned observation window has started`() {
        val run = activatableRun().copy(
            windowStart = now.minusSeconds(1),
            windowEnd = now.plusSeconds(1),
        )

        assertThat(policy.assess(run, semantics(run), clockSample(run), tickers(run)).failures)
            .containsExactly(ObservationActivationFailure.OBSERVATION_WINDOW_ALREADY_STARTED)
    }

    @Test
    fun `requires explicit storage and benchmark rights and no unknown decision`() {
        val run = activatableRun().copy(
            rights = ObservationTestFixtures.rights(
                storage = RightsDecision.DENIED,
                benchmark = RightsDecision.DENIED,
                replay = RightsDecision.UNKNOWN,
            ),
        )

        assertThat(policy.assess(run, semantics(run), clockSample(run), tickers(run)).failures)
            .containsExactlyInAnyOrder(
                ObservationActivationFailure.RIGHTS_UNKNOWN,
                ObservationActivationFailure.STORAGE_NOT_ALLOWED,
                ObservationActivationFailure.BENCHMARK_NOT_ALLOWED,
            )
    }

    @Test
    fun `rejects a dirty source tree so the benchmark code identity is reproducible`() {
        val run = activatableRun().copy(sourceTreeDirty = true)

        assertThat(policy.assess(run, semantics(run), clockSample(run), tickers(run)).failures)
            .containsExactly(ObservationActivationFailure.SOURCE_TREE_DIRTY)
    }

    @Test
    fun `requires all semantics dimensions to be known and timestamp meaning to be complete`() {
        val run = activatableRun()
        val unknownSemantics = semantics(run).copy(
            venue = MarketVenue.UNKNOWN,
            session = MarketSession.UNKNOWN,
            interval = CandleInterval.UNKNOWN,
            timestampOrigin = TimestampOrigin.UNKNOWN,
            candleTimeConvention = CandleTimeConvention.UNKNOWN,
            adjustmentMode = AdjustmentMode.UNKNOWN,
            correctionPolicy = CorrectionPolicy.UNKNOWN,
            emptyMinutePolicy = EmptyMinutePolicy.UNKNOWN,
            volumeUnit = VolumeUnit.UNKNOWN,
            providerEventIdScope = ProviderEventIdScope.UNKNOWN,
            timestampPrecisionMicros = null,
            providerZoneId = null,
            confirmedAt = null,
        )

        assertThat(policy.assess(run, unknownSemantics, clockSample(run), tickers(run)).failures)
            .contains(
                ObservationActivationFailure.SEMANTICS_UNKNOWN,
                ObservationActivationFailure.TIMESTAMP_MEANING_INCOMPLETE,
                ObservationActivationFailure.SEMANTICS_UNCONFIRMED,
                ObservationActivationFailure.PROVIDER_EVENT_TIME_REQUIRED,
            )
    }

    @Test
    fun `rejects semantics clock and ticker evidence belonging to another provider scope`() {
        val run = activatableRun()
        val otherScope = ObservationTestFixtures.scope(
            runId = UUID.fromString("10000000-0000-0000-0000-000000000002"),
            provider = "toss",
        )

        assertThat(
            policy.assess(
                run,
                ObservationTestFixtures.semantics(otherScope),
                ObservationTestFixtures.clockSample(otherScope),
                ObservationTestFixtures.expectedTickers(otherScope),
            ).failures,
        ).containsExactlyInAnyOrder(
            ObservationActivationFailure.SEMANTICS_SCOPE_MISMATCH,
            ObservationActivationFailure.CLOCK_SCOPE_MISMATCH,
            ObservationActivationFailure.EXPECTED_TICKER_SCOPE_MISMATCH,
        )
    }

    @Test
    fun `rejects missing count order and checksum changes in the frozen ticker set`() {
        val run = activatableRun()
        assertThat(policy.assess(run, semantics(run), clockSample(run), emptyList()).failures)
            .contains(
                ObservationActivationFailure.EXPECTED_TICKERS_MISSING,
                ObservationActivationFailure.EXPECTED_TICKER_COUNT_MISMATCH,
                ObservationActivationFailure.EXPECTED_TICKER_CHECKSUM_MISMATCH,
            )

        val badOrder = listOf(ObservationExpectedTicker(run.scope, ticker = "100001", ordinal = 1))
        assertThat(policy.assess(run, semantics(run), clockSample(run), badOrder).failures)
            .containsExactly(ObservationActivationFailure.EXPECTED_TICKER_ORDER_INVALID)

        val badCount = ObservationTestFixtures.expectedTickers(run.scope, listOf("100001", "100002"))
        assertThat(policy.assess(run, semantics(run), clockSample(run), badCount).failures)
            .contains(
                ObservationActivationFailure.EXPECTED_TICKER_COUNT_MISMATCH,
                ObservationActivationFailure.EXPECTED_TICKER_CHECKSUM_MISMATCH,
            )

        val wrongChecksum = run.copy(tickerSetChecksumSha256 = "e".repeat(64))
        assertThat(policy.assess(wrongChecksum, semantics(run), clockSample(run), tickers(run)).failures)
            .containsExactly(ObservationActivationFailure.EXPECTED_TICKER_CHECKSUM_MISMATCH)
    }

    @Test
    fun `provider observations require a synchronized trusted and healthy latest clock`() {
        val run = activatableRun().copy(origin = ObservationOrigin.PROVIDER)
        val sample = clockSample(run).copy(
            sampledAt = now.minusSeconds(61),
            sampleSequence = 2,
            synchronized = false,
            source = ObservationClockSource.SYSTEM,
        )

        assertThat(policy.assess(run, semantics(run), sample, tickers(run)).failures)
            .containsExactlyInAnyOrder(
                ObservationActivationFailure.CLOCK_SAMPLE_NOT_LATEST,
                ObservationActivationFailure.CLOCK_NOT_SYNCHRONIZED,
                ObservationActivationFailure.TRUSTED_CLOCK_SOURCE_REQUIRED,
                ObservationActivationFailure.CLOCK_GATE_FAILED,
            )
    }

    @Test
    fun `realtime and kospi roles require exactly provider event timestamps`() {
        val realtime = activatableRun()
        listOf(
            TimestampOrigin.PROVIDER_CANDLE,
            TimestampOrigin.SERVER_RECEIVE,
            TimestampOrigin.NOT_APPLICABLE,
        ).forEach { origin ->
            assertThat(
                policy.assess(
                    realtime,
                    semantics(realtime).copy(timestampOrigin = origin),
                    clockSample(realtime),
                    tickers(realtime),
                ).failures,
            ).contains(ObservationActivationFailure.PROVIDER_EVENT_TIME_REQUIRED)
        }

        val kospi = realtime.copy(role = ObservationRole.KOSPI_FEED)
        assertThat(
            policy.assess(
                kospi,
                semantics(kospi).copy(timestampOrigin = TimestampOrigin.PROVIDER_CANDLE),
                clockSample(kospi),
                tickers(kospi),
            ).failures,
        ).containsExactly(ObservationActivationFailure.PROVIDER_EVENT_TIME_REQUIRED)
    }

    @Test
    fun `candle reference requires exactly provider candle timestamps and candle conventions`() {
        val reference = activatableRun().copy(role = ObservationRole.CANDLE_REFERENCE)
        val wrongOrigin = semantics(reference).copy(timestampOrigin = TimestampOrigin.PROVIDER_EVENT)
        assertThat(policy.assess(reference, wrongOrigin, clockSample(reference), tickers(reference)).failures)
            .containsExactly(ObservationActivationFailure.PROVIDER_CANDLE_TIME_REQUIRED)

        val incompleteCandle = semantics(reference).copy(
            timestampOrigin = TimestampOrigin.PROVIDER_CANDLE,
            candleTimeConvention = CandleTimeConvention.NOT_APPLICABLE,
            adjustmentMode = AdjustmentMode.NOT_APPLICABLE,
        )
        assertThat(policy.assess(reference, incompleteCandle, clockSample(reference), tickers(reference)).failures)
            .containsExactlyInAnyOrder(
                ObservationActivationFailure.CANDLE_TIME_CONVENTION_REQUIRED,
                ObservationActivationFailure.ADJUSTMENT_MODE_REQUIRED,
            )
    }

    @Test
    fun `require activatable exposes every failed gate`() {
        val run = activatableRun().copy(
            rights = ObservationTestFixtures.rights(storage = RightsDecision.DENIED),
            latestClockSampleSequence = null,
        )

        val exception = assertThrows<ObservationActivationRejectedException> {
            policy.requireActivatable(run, semantics = null, clockSample = null, expectedTickers = emptyList())
        }

        assertThat(exception.failures)
            .containsExactlyInAnyOrder(
                ObservationActivationFailure.STORAGE_NOT_ALLOWED,
                ObservationActivationFailure.EXPECTED_TICKERS_MISSING,
                ObservationActivationFailure.EXPECTED_TICKER_COUNT_MISMATCH,
                ObservationActivationFailure.EXPECTED_TICKER_CHECKSUM_MISMATCH,
                ObservationActivationFailure.SEMANTICS_MISSING,
                ObservationActivationFailure.CLOCK_SAMPLE_MISSING,
            )
    }

    private fun activatableRun(): ObservationRun = ObservationTestFixtures.run().copy(
        latestClockSampleSequence = 1,
    )

    private fun semantics(run: ObservationRun) = ObservationTestFixtures.semantics(run.scope)

    private fun clockSample(run: ObservationRun) = ObservationTestFixtures.clockSample(run.scope)

    private fun tickers(run: ObservationRun) = ObservationTestFixtures.expectedTickers(run.scope)
}
