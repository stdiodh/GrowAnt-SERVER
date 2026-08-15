package com.growant.market.observation.persistence

import com.growant.market.candle.MinuteCandle
import com.growant.market.candle.persistence.MinuteCandleStore
import com.growant.market.observation.CandleObservationSource
import com.growant.market.observation.ObservationCleanupResult
import com.growant.market.observation.RestPollOutcome
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationScope
import com.growant.market.observation.ObservationTestFixtures
import com.growant.market.observation.ObservationTickerSetChecksum
import com.growant.market.observation.RightsDecision
import com.growant.market.observation.application.MarketObservationService
import com.growant.market.observation.application.ObservationCleanupService
import com.growant.market.observation.policy.ClockHealthPolicy
import com.growant.market.observation.policy.ObservationActivationPolicy
import com.growant.market.observation.port.ObservationAppendResult
import com.growant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class MarketObservationStoreIT(
    @Autowired private val store: MarketObservationStore,
    @Autowired private val minuteCandleStore: MinuteCandleStore,
    @Autowired private val jdbc: NamedParameterJdbcTemplate,
    @Autowired private val transactionManager: PlatformTransactionManager,
) : PostgresIntegrationTest() {
    @Test
    fun `provider identifiers and candle revisions are append-only but REST request UUID is unique`() {
        val scope = scope("51000000-0000-0000-0000-000000000001", "kis")
        createRunningRun(scope)

        val firstTick = ObservationTestFixtures.tick(scope, localReceiveSequence = 1, providerEventId = "same-event")
        val repeatedTick = firstTick.copy(localReceiveSequence = 2)
        assertThat(store.appendTick(firstTick)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(store.appendTick(repeatedTick)).isEqualTo(ObservationAppendResult.APPENDED)

        val firstRequestId = UUID.fromString("52000000-0000-0000-0000-000000000001")
        val firstPoll = ObservationTestFixtures.restPoll(scope, firstRequestId)
        assertThat(store.appendRestPoll(firstPoll)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThatThrownBy {
            store.appendRestPoll(
                firstPoll.copy(
                    observedAt = firstPoll.observedAt.plusSeconds(1),
                    normalizedAt = firstPoll.normalizedAt?.plusSeconds(1),
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
            store.appendRestPoll(
                ObservationTestFixtures.restPoll(
                    scope,
                    UUID.fromString("52000000-0000-0000-0000-000000000002"),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)

        val firstCandle = ObservationTestFixtures.candle(scope, observationSequence = 1).copy(
            restRequestId = firstRequestId,
        )
        val repeatedRevision = firstCandle.copy(observationSequence = 2)
        assertThat(store.appendCandle(firstCandle)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(store.appendCandle(repeatedRevision)).isEqualTo(ObservationAppendResult.APPENDED)

        val snapshot = store.evidenceSnapshot(scope)!!
        assertThat(snapshot.tickCount).isEqualTo(2)
        assertThat(snapshot.restPollCount).isEqualTo(2)
        assertThat(snapshot.candleCount).isEqualTo(2)
        assertThat(countRows("market_observation_ticks", scope, "provider_event_id = 'same-event'"))
            .isEqualTo(2)
        assertThat(countRows("market_observation_rest_polls", scope, "request_id = '$firstRequestId'"))
            .isEqualTo(1)
        assertThat(countRows("market_observation_candles", scope, "provider_revision = 'revision-1'"))
            .isEqualTo(2)
    }

    @Test
    fun `activation atomically freezes the latest clock and immutable five ticker configuration`() {
        val scope = scope("51000000-0000-0000-0000-000000000002", "kis")
        val tickers = listOf("005930", "000660", "035720", "035420", "005380")
        store.createRun(
            ObservationTestFixtures.run(
                scope = scope,
                expectedTickerCount = tickers.size,
                tickerSetChecksumSha256 = ObservationTickerSetChecksum.sha256(tickers),
            ),
        )
        store.createExpectedTickers(ObservationTestFixtures.expectedTickers(scope, tickers))
        store.createSemantics(ObservationTestFixtures.semantics(scope))
        assertThat(store.appendClockSample(ObservationTestFixtures.clockSample(scope, sampleSequence = 1)))
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusMillis(1),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)

        assertThat(store.activateRun(scope, expectedClockSampleSequence = 1, ACTIVATED_AT)).isFalse()
        assertThat(store.activateRun(scope, expectedClockSampleSequence = 2, ACTIVATED_AT)).isTrue()

        val run = store.findRun(scope)!!
        assertThat(run.state).isEqualTo(ObservationRunState.RUNNING)
        assertThat(run.latestClockSampleSequence).isEqualTo(2)
        assertThat(run.activationClockSampleSequence).isEqualTo(2)
        assertThat(store.findExpectedTickers(scope).map { it.ticker }).containsExactlyElementsOf(tickers)
        val bundle = store.evidenceBundle(scope)!!
        assertThat(bundle.schemaVersion).isEqualTo(4)
        assertThat(bundle.activationClockSample?.sampleSequence).isEqualTo(2)
        assertThat(bundle.expectedTickers).hasSize(5)
    }

    @Test
    fun `activation rejects a run whose planned observation window already ended`() {
        val scope = scope("51000000-0000-0000-0000-000000000015", "kis")
        val run = ObservationTestFixtures.run(scope = scope)
        store.createRun(run)
        store.createExpectedTickers(ObservationTestFixtures.expectedTickers(scope))
        store.createSemantics(ObservationTestFixtures.semantics(scope))
        assertThat(store.appendClockSample(ObservationTestFixtures.clockSample(scope)))
            .isEqualTo(ObservationAppendResult.APPENDED)

        assertThat(
            store.activateRun(
                scope,
                expectedClockSampleSequence = 1,
                changedAt = run.windowEnd,
            ),
        ).isFalse()
        assertThat(store.findRun(scope)!!.state).isEqualTo(ObservationRunState.PLANNED)
    }

    @Test
    fun `an actual backward clock regression is preserved and atomically invalidates the run`() {
        val scope = scope("51000000-0000-0000-0000-000000000020", "kis")
        val now = Instant.now()
        val run = ObservationTestFixtures.run(
            scope = scope,
            createdAt = now.minusSeconds(10),
            windowStart = now.plusSeconds(60),
            windowEnd = now.plusSeconds(180),
            retentionUntil = now.plusSeconds(3_600),
        )
        store.createRun(run)
        store.createExpectedTickers(ObservationTestFixtures.expectedTickers(scope))
        store.createSemantics(ObservationTestFixtures.semantics(scope))
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope).copy(sampledAt = now.minusSeconds(4)),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(store.activateRun(scope, 1, now.minusSeconds(3))).isTrue()

        val backwardClock = Clock.fixed(now.minusSeconds(20), ZoneOffset.UTC)
        val service = MarketObservationService(
            repository = store,
            activationPolicy = ObservationActivationPolicy(
                clock = backwardClock,
                clockHealthPolicy = ClockHealthPolicy(backwardClock),
            ),
            clock = backwardClock,
        )
        TransactionTemplate(transactionManager).executeWithoutResult {
            service.recordClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = now.minusSeconds(5),
                ),
            )
        }

        val invalid = store.findRun(scope)!!
        assertThat(invalid.state).isEqualTo(ObservationRunState.INVALID)
        assertThat(invalid.latestClockSampleSequence).isEqualTo(1)
        assertThat(invalid.completedAt).isEqualTo(now.minusSeconds(3))
        assertThat(store.findClockSample(scope, 2)).isNotNull()
        assertThat(store.evidenceSnapshot(scope)!!.clockSampleCount).isEqualTo(2)
    }

    @Test
    fun `append requires a running run allowed storage and benchmark rights`() {
        val plannedScope = scope("51000000-0000-0000-0000-000000000003", "kis")
        store.createRun(ObservationTestFixtures.run(scope = plannedScope))
        assertThat(store.appendTick(ObservationTestFixtures.tick(plannedScope)))
            .isEqualTo(ObservationAppendResult.REJECTED)

        val storageDeniedScope = scope("51000000-0000-0000-0000-000000000004", "kis")
        createRunningRun(storageDeniedScope)
        updateRight(storageDeniedScope, "storage_right", RightsDecision.DENIED)
        assertThat(store.appendTick(ObservationTestFixtures.tick(storageDeniedScope)))
            .isEqualTo(ObservationAppendResult.REJECTED)

        val benchmarkDeniedScope = scope("51000000-0000-0000-0000-000000000005", "kis")
        createRunningRun(benchmarkDeniedScope)
        updateRight(benchmarkDeniedScope, "benchmark_right", RightsDecision.DENIED)
        assertThat(store.appendTick(ObservationTestFixtures.tick(benchmarkDeniedScope)))
            .isEqualTo(ObservationAppendResult.REJECTED)

        assertThat(countRows("market_observation_ticks", plannedScope)).isZero()
        assertThat(countRows("market_observation_ticks", storageDeniedScope)).isZero()
        assertThat(countRows("market_observation_ticks", benchmarkDeniedScope)).isZero()
    }

    @Test
    fun `expected ticker membership gates every symbol-bearing observation`() {
        val scope = scope("51000000-0000-0000-0000-000000000006", "kis")
        createRunningRun(scope)
        val unknownTicker = "999999"

        assertThat(store.appendRestPoll(ObservationTestFixtures.restPoll(scope).copy(ticker = unknownTicker)))
            .isEqualTo(ObservationAppendResult.REJECTED)
        assertThat(
            store.appendTick(
                ObservationTestFixtures.tick(scope).copy(
                    expectedTicker = unknownTicker,
                    reportedTicker = unknownTicker,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)
        assertThat(
            store.appendCandle(
                ObservationTestFixtures.candle(scope).copy(
                    ticker = unknownTicker,
                    source = CandleObservationSource.LOCAL_AGGREGATE,
                    restRequestId = null,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)
        assertThat(store.appendFaultEvent(ObservationTestFixtures.faultEvent(scope).copy(ticker = unknownTicker)))
            .isEqualTo(ObservationAppendResult.REJECTED)
    }

    @Test
    fun `observation append gates use the documented half-open benchmark window`() {
        val scope = scope("51000000-0000-0000-0000-000000000016", "kis")
        val run = ObservationTestFixtures.run(scope = scope)
        createRunningRun(scope)

        val acceptedPoll = ObservationTestFixtures.restPoll(
            scope,
            UUID.fromString("52000000-0000-0000-0000-000000000016"),
        ).copy(
            requestStartedAt = run.windowStart,
            observedAt = run.windowStart,
            normalizedAt = run.windowStart,
        )
        assertThat(store.appendRestPoll(acceptedPoll)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendRestPoll(
                acceptedPoll.copy(
                    requestId = UUID.fromString("52000000-0000-0000-0000-000000000017"),
                    pollRunId = UUID.fromString("52000000-0000-0000-0000-000000000017"),
                    requestStartedAt = run.windowEnd,
                    observedAt = run.windowEnd,
                    normalizedAt = run.windowEnd,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        val acceptedTick = ObservationTestFixtures.tick(scope).copy(
            providerOccurredAt = run.windowStart,
            socketReceivedAt = run.windowStart,
            normalizedAt = run.windowStart,
        )
        assertThat(store.appendTick(acceptedTick)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendTick(
                acceptedTick.copy(
                    localReceiveSequence = 2,
                    providerOccurredAt = run.windowEnd,
                    socketReceivedAt = run.windowEnd,
                    normalizedAt = run.windowEnd,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        val acceptedCandle = ObservationTestFixtures.candle(scope).copy(
            bucketStart = run.windowStart,
            observedAt = run.windowStart,
            source = CandleObservationSource.LOCAL_AGGREGATE,
            restRequestId = null,
        )
        assertThat(store.appendCandle(acceptedCandle)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendCandle(
                acceptedCandle.copy(
                    bucketStart = run.windowEnd,
                    observationSequence = 2,
                    observedAt = run.windowEnd,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        val acceptedFault = ObservationTestFixtures.faultEvent(scope).copy(
            observedAt = run.windowStart,
            gapFrom = null,
            gapTo = null,
        )
        assertThat(store.appendFaultEvent(acceptedFault)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendFaultEvent(
                acceptedFault.copy(
                    eventSequence = 2,
                    observedAt = run.windowEnd,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)
    }

    @Test
    fun `normal ticks require a healthy latest clock sample at receive time`() {
        val scope = scope("51000000-0000-0000-0000-000000000017", "kis")
        createRunningRun(scope)

        assertThat(
            store.appendTick(
                ObservationTestFixtures.tick(scope).copy(
                    providerOccurredAt = ObservationTestFixtures.baseTime.plusSeconds(60),
                    socketReceivedAt = ObservationTestFixtures.baseTime.plusSeconds(61),
                    normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(61),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(5),
                    synchronized = false,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendTick(
                ObservationTestFixtures.tick(scope).copy(
                    clockSampleSequence = 2,
                    providerOccurredAt = ObservationTestFixtures.baseTime.plusSeconds(6),
                    socketReceivedAt = ObservationTestFixtures.baseTime.plusSeconds(6),
                    normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(6),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 3).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(7),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendTick(
                ObservationTestFixtures.tick(scope).copy(
                    localReceiveSequence = 2,
                    clockSampleSequence = 2,
                    providerOccurredAt = ObservationTestFixtures.baseTime.plusSeconds(8),
                    socketReceivedAt = ObservationTestFixtures.baseTime.plusSeconds(8),
                    normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(8),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)
        assertThat(
            store.appendTick(
                ObservationTestFixtures.tick(scope).copy(
                    localReceiveSequence = 3,
                    clockSampleSequence = 3,
                    providerOccurredAt = ObservationTestFixtures.baseTime.plusSeconds(8),
                    socketReceivedAt = ObservationTestFixtures.baseTime.plusSeconds(8),
                    normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(8),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
    }

    @Test
    fun `observations use the healthy clock sample that was latest at their event time`() {
        val scope = scope("51000000-0000-0000-0000-000000000019", "kis")
        createRunningRun(scope)
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(20),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)

        assertThat(store.appendTick(ObservationTestFixtures.tick(scope)))
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendRestPoll(
                ObservationTestFixtures.restPoll(
                    scope,
                    UUID.fromString("52000000-0000-0000-0000-000000000021"),
                ).copy(
                    requestStartedAt = ObservationTestFixtures.baseTime.plusSeconds(10),
                    observedAt = ObservationTestFixtures.baseTime.plusSeconds(11),
                    normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(11),
                    returnedCandleCount = 0,
                    eligibleCandleCount = 0,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendFaultEvent(
                ObservationTestFixtures.faultEvent(scope).copy(
                    observedAt = ObservationTestFixtures.baseTime.plusSeconds(12),
                    gapFrom = ObservationTestFixtures.baseTime.plusSeconds(12),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
    }

    @Test
    fun `provider REST candles require a successful in-range poll and exact returned count`() {
        val scope = scope("51000000-0000-0000-0000-000000000018", "toss")
        createRunningRun(scope)

        val failedRequestId = UUID.fromString("52000000-0000-0000-0000-000000000018")
        val failedPoll = ObservationTestFixtures.restPoll(scope, failedRequestId).copy(
            normalizedAt = null,
            outcome = RestPollOutcome.HTTP_ERROR,
            httpStatus = 500,
            returnedCandleCount = 0,
            eligibleCandleCount = 0,
        )
        assertThat(store.appendRestPoll(failedPoll)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendCandle(
                ObservationTestFixtures.candle(scope).copy(restRequestId = failedRequestId),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        val shortRequestId = UUID.fromString("52000000-0000-0000-0000-000000000019")
        val shortPoll = ObservationTestFixtures.restPoll(scope, shortRequestId).copy(
            requestedFrom = ObservationTestFixtures.baseTime.minusSeconds(60),
            requestedTo = ObservationTestFixtures.baseTime.plusSeconds(20),
            returnedCandleCount = 1,
            eligibleCandleCount = 0,
        )
        assertThat(store.appendRestPoll(shortPoll)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendCandle(
                ObservationTestFixtures.candle(scope).copy(restRequestId = shortRequestId),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        val countedRequestId = UUID.fromString("52000000-0000-0000-0000-000000000020")
        assertThat(
            store.appendRestPoll(ObservationTestFixtures.restPoll(scope, countedRequestId)),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendCandle(
                ObservationTestFixtures.candle(scope).copy(
                    bucketStart = ObservationTestFixtures.baseTime.plusSeconds(1),
                    observedAt = ObservationTestFixtures.baseTime.plusSeconds(1),
                    restRequestId = countedRequestId,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)

        assertThat(
            store.compareAndSetRunState(
                scope,
                ObservationRunState.RUNNING,
                ObservationRunState.COMPLETED,
                ObservationTestFixtures.baseTime.plusSeconds(120),
            ),
        ).isFalse()
        assertThat(
            store.appendCandle(
                ObservationTestFixtures.candle(scope).copy(
                    observedAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                    restRequestId = countedRequestId,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.compareAndSetRunState(
                scope,
                ObservationRunState.RUNNING,
                ObservationRunState.COMPLETED,
                ObservationTestFixtures.baseTime.plusSeconds(120),
            ),
        ).isTrue()
    }

    @Test
    fun `REST pagination requires an exact contiguous chain and one terminal page`() {
        val scope = scope("51000000-0000-0000-0000-000000000022", "toss")
        createRunningRun(scope)
        val rootRequestId = UUID.fromString("52000000-0000-0000-0000-000000000022")
        val root = ObservationTestFixtures.restPoll(scope, rootRequestId).copy(
            requestCursor = "2026-08-17T15:30:00+09:00",
            nextCursor = "cursor-page-1",
            pollTerminal = false,
            returnedCandleCount = 0,
            eligibleCandleCount = 0,
        )
        assertThat(store.appendRestPoll(root)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            jdbc.queryForObject(
                """
                SELECT request_cursor
                FROM market_observation_rest_polls
                WHERE run_id = :runId AND provider = :provider AND request_id = :requestId
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("runId", scope.runId)
                    .addValue("provider", scope.provider)
                    .addValue("requestId", rootRequestId),
                String::class.java,
            ),
        ).isEqualTo("2026-08-17T15:30:00+09:00")

        val terminalPage = root.copy(
            requestId = UUID.fromString("52000000-0000-0000-0000-000000000023"),
            pollRunId = rootRequestId,
            pageOrdinal = 1,
            requestCursor = "cursor-page-1",
            nextCursor = null,
            pollTerminal = true,
            requestStartedAt = ObservationTestFixtures.baseTime.plusSeconds(3),
            observedAt = ObservationTestFixtures.baseTime.plusSeconds(4),
            normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(4),
        )
        assertThat(
            store.appendRestPoll(
                terminalPage.copy(
                    requestId = UUID.fromString("52000000-0000-0000-0000-000000000024"),
                    pageOrdinal = 2,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)
        assertThat(
            store.appendRestPoll(
                terminalPage.copy(
                    requestId = UUID.fromString("52000000-0000-0000-0000-000000000025"),
                    requestCursor = "wrong-cursor",
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)
        assertThat(
            store.appendRestPoll(
                terminalPage.copy(
                    requestId = UUID.fromString("52000000-0000-0000-0000-000000000026"),
                    requestedFrom = terminalPage.requestedFrom.plusSeconds(1),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)
        assertThat(
            store.appendRestPoll(
                terminalPage.copy(
                    requestId = UUID.fromString("52000000-0000-0000-0000-000000000027"),
                    requestStartedAt = ObservationTestFixtures.baseTime.plusSeconds(1),
                    observedAt = ObservationTestFixtures.baseTime.plusSeconds(2),
                    normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(2),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.compareAndSetRunState(
                scope,
                ObservationRunState.RUNNING,
                ObservationRunState.COMPLETED,
                ObservationTestFixtures.baseTime.plusSeconds(120),
            ),
        ).isFalse()

        assertThat(store.appendRestPoll(terminalPage)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThatThrownBy {
            store.appendRestPoll(
                terminalPage.copy(
                    requestId = UUID.fromString("52000000-0000-0000-0000-000000000028"),
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
            store.appendRestPoll(
                terminalPage.copy(
                    requestId = UUID.fromString("52000000-0000-0000-0000-000000000029"),
                    pageOrdinal = 2,
                    requestCursor = "cursor-after-terminal",
                ),
            ),
        ).isEqualTo(ObservationAppendResult.REJECTED)

        val snapshot = store.evidenceSnapshot(scope)!!
        assertThat(snapshot.restPollCount).isEqualTo(2)
        assertThat(snapshot.restPollRunCount).isEqualTo(1)
        assertThat(
            store.compareAndSetRunState(
                scope,
                ObservationRunState.RUNNING,
                ObservationRunState.COMPLETED,
                ObservationTestFixtures.baseTime.plusSeconds(120),
            ),
        ).isTrue()
    }

    @Test
    fun `completion preserves inclusive boundary candles for every REST page`() {
        val scope = scope("51000000-0000-0000-0000-000000000023", "toss")
        createRunningRun(scope)
        val rootRequestId = UUID.fromString("52000000-0000-0000-0000-000000000030")
        val root = ObservationTestFixtures.restPoll(scope, rootRequestId).copy(
            nextCursor = "inclusive-boundary",
            pollTerminal = false,
        )
        assertThat(store.appendRestPoll(root)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendCandle(
                ObservationTestFixtures.candle(scope, observationSequence = 1).copy(
                    restRequestId = rootRequestId,
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)

        val finalRequestId = UUID.fromString("52000000-0000-0000-0000-000000000031")
        val finalPage = root.copy(
            requestId = finalRequestId,
            pollRunId = rootRequestId,
            pageOrdinal = 1,
            requestCursor = "inclusive-boundary",
            nextCursor = null,
            pollTerminal = true,
            requestStartedAt = ObservationTestFixtures.baseTime.plusSeconds(3),
            observedAt = ObservationTestFixtures.baseTime.plusSeconds(4),
            normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(4),
        )
        assertThat(store.appendRestPoll(finalPage)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.compareAndSetRunState(
                scope,
                ObservationRunState.RUNNING,
                ObservationRunState.COMPLETED,
                ObservationTestFixtures.baseTime.plusSeconds(120),
            ),
        ).isFalse()

        assertThat(
            store.appendCandle(
                ObservationTestFixtures.candle(scope, observationSequence = 2).copy(
                    restRequestId = finalRequestId,
                    observedAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.compareAndSetRunState(
                scope,
                ObservationRunState.RUNNING,
                ObservationRunState.COMPLETED,
                ObservationTestFixtures.baseTime.plusSeconds(120),
            ),
        ).isTrue()

        val snapshot = store.evidenceSnapshot(scope)!!
        assertThat(snapshot.restPollCount).isEqualTo(2)
        assertThat(snapshot.restPollRunCount).isEqualTo(1)
        assertThat(snapshot.candleCount).isEqualTo(2)
    }

    @Test
    fun `evidence checksum includes raw REST pagination provenance`() {
        val first = scope("51000000-0000-0000-0000-000000000024", "toss")
        val second = scope("51000000-0000-0000-0000-000000000025", "toss")
        createRunningRun(first)
        createRunningRun(second)
        val rootRequestId = UUID.fromString("52000000-0000-0000-0000-000000000032")
        val finalRequestId = UUID.fromString("52000000-0000-0000-0000-000000000033")

        listOf(
            first to "2026-08-17T09:31:00+09:00",
            second to "2026-08-17T00:31:00Z",
        ).forEach { (observationScope, rawCursor) ->
            val root = ObservationTestFixtures.restPoll(observationScope, rootRequestId).copy(
                nextCursor = rawCursor,
                pollTerminal = false,
                returnedCandleCount = 0,
                eligibleCandleCount = 0,
            )
            assertThat(store.appendRestPoll(root)).isEqualTo(ObservationAppendResult.APPENDED)
            assertThat(
                store.appendRestPoll(
                    root.copy(
                        requestId = finalRequestId,
                        pollRunId = rootRequestId,
                        pageOrdinal = 1,
                        requestCursor = rawCursor,
                        nextCursor = null,
                        pollTerminal = true,
                        requestStartedAt = ObservationTestFixtures.baseTime.plusSeconds(3),
                        observedAt = ObservationTestFixtures.baseTime.plusSeconds(4),
                        normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(4),
                    ),
                ),
            ).isEqualTo(ObservationAppendResult.APPENDED)
        }

        val firstSnapshot = store.evidenceSnapshot(first)!!
        val secondSnapshot = store.evidenceSnapshot(second)!!
        assertThat(firstSnapshot.restPollCount).isEqualTo(2)
        assertThat(firstSnapshot.restPollRunCount).isEqualTo(1)
        assertThat(secondSnapshot.restPollRunCount).isEqualTo(1)
        assertThat(firstSnapshot.rowChecksumSha256).isNotEqualTo(secondSnapshot.rowChecksumSha256)
        assertThat(store.evidenceSnapshot(first)!!.rowChecksumSha256).isEqualTo(firstSnapshot.rowChecksumSha256)
    }

    @Test
    fun `run id and provider jointly isolate otherwise identical observations`() {
        val first = scope("51000000-0000-0000-0000-000000000007", "kis")
        val otherProvider = scope("51000000-0000-0000-0000-000000000007", "toss")
        val otherRun = scope("51000000-0000-0000-0000-000000000008", "kis")

        listOf(first, otherProvider, otherRun).forEach { observationScope ->
            createRunningRun(observationScope)
            assertThat(store.appendTick(ObservationTestFixtures.tick(observationScope)))
                .isEqualTo(ObservationAppendResult.APPENDED)
        }

        assertThat(listOf(first, otherProvider, otherRun).map { store.evidenceSnapshot(it)!!.tickCount })
            .containsExactly(1, 1, 1)
        assertThat(countRows("market_observation_ticks", first)).isEqualTo(1)
        assertThat(countRows("market_observation_ticks", otherProvider)).isEqualTo(1)
        assertThat(countRows("market_observation_ticks", otherRun)).isEqualTo(1)
    }

    @Test
    fun `an exact tick identity duplicate fails instead of overwriting evidence`() {
        val scope = scope("51000000-0000-0000-0000-000000000009", "kis")
        createRunningRun(scope)
        val tick = ObservationTestFixtures.tick(scope)
        assertThat(store.appendTick(tick)).isEqualTo(ObservationAppendResult.APPENDED)

        assertThatThrownBy { store.appendTick(tick) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(countRows("market_observation_ticks", scope)).isEqualTo(1)
    }

    @Test
    fun `evidence checksum is deterministic and scoped data changes it`() {
        val first = scope("51000000-0000-0000-0000-000000000010", "kis")
        val second = scope("51000000-0000-0000-0000-000000000011", "kis")
        createFullRunningRun(first)
        createFullRunningRun(second)

        val firstChecksum = store.evidenceSnapshot(first)!!.rowChecksumSha256
        assertThat(store.evidenceSnapshot(first)!!.rowChecksumSha256).isEqualTo(firstChecksum)
        assertThat(store.evidenceSnapshot(second)!!.rowChecksumSha256).isEqualTo(firstChecksum)
        assertThat(firstChecksum).matches("[0-9a-f]{64}")

        assertThat(
            store.appendTick(
                ObservationTestFixtures.tick(second, localReceiveSequence = 2).copy(
                    clockSampleSequence = 2,
                    providerOccurredAt = ObservationTestFixtures.baseTime.plusSeconds(118),
                    socketReceivedAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                    normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                ),
            ),
        )
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(store.evidenceSnapshot(second)!!.rowChecksumSha256).isNotEqualTo(firstChecksum)
        assertThat(store.evidenceSnapshot(first)!!.rowChecksumSha256).isEqualTo(firstChecksum)
    }

    @Test
    fun `cleanup deletes completed evidence and preserves complete deletion counts`() {
        val scope = scope("51000000-0000-0000-0000-000000000012", "kis")
        val cleanupId = UUID.fromString("53000000-0000-0000-0000-000000000001")
        val databaseNow = currentDatabaseTime()
        val observationBase = databaseNow.minusSeconds(300)
        val retentionUntil = databaseNow.minusSeconds(1)
        val cleanupAt = databaseNow.plusSeconds(1)
        createFullRunningRun(
            scope = scope,
            retentionUntil = databaseNow.plusSeconds(3_600),
            baseTime = observationBase,
        )
        val paginatedRootId = UUID.fromString("52000000-0000-0000-0000-000000000034")
        val paginatedRoot = ObservationTestFixtures.restPoll(scope, paginatedRootId).copy(
            nextCursor = "cleanup-page-cursor",
            pollTerminal = false,
            requestStartedAt = observationBase.plusSeconds(3),
            observedAt = observationBase.plusSeconds(4),
            normalizedAt = observationBase.plusSeconds(4),
            requestedFrom = observationBase.plusSeconds(1),
            requestedTo = observationBase.plusSeconds(120),
            returnedCandleCount = 0,
            eligibleCandleCount = 0,
        )
        assertThat(store.appendRestPoll(paginatedRoot)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendRestPoll(
                paginatedRoot.copy(
                    requestId = UUID.fromString("52000000-0000-0000-0000-000000000035"),
                    pollRunId = paginatedRootId,
                    pageOrdinal = 1,
                    requestCursor = "cleanup-page-cursor",
                    nextCursor = null,
                    pollTerminal = true,
                    requestStartedAt = observationBase.plusSeconds(5),
                    observedAt = observationBase.plusSeconds(6),
                    normalizedAt = observationBase.plusSeconds(6),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.compareAndSetRunState(
                scope,
                ObservationRunState.RUNNING,
                ObservationRunState.COMPLETED,
                observationBase.plusSeconds(120),
            ),
        ).isTrue()
        updateRetention(scope, retentionUntil)

        val audit = store.cleanupTerminalRun(scope, cleanupId, cleanupAt)

        assertThat(audit.result).isEqualTo(ObservationCleanupResult.DELETED)
        assertThat(audit.terminalState).isEqualTo(ObservationRunState.COMPLETED)
        assertThat(audit.semanticsDeleted).isEqualTo(1)
        assertThat(audit.expectedTickersDeleted).isEqualTo(1)
        assertThat(audit.clockSamplesDeleted).isEqualTo(2)
        assertThat(audit.restPollsDeleted).isEqualTo(3)
        assertThat(audit.ticksDeleted).isEqualTo(1)
        assertThat(audit.candlesDeleted).isEqualTo(1)
        assertThat(audit.faultEventsDeleted).isEqualTo(1)
        assertThat(store.findRun(scope)).isNull()
        assertThat(countCleanupAudits(cleanupId)).isEqualTo(1)
    }

    @Test
    fun `cleanup atomically invalidates and deletes an expired running run`() {
        val scope = scope("51000000-0000-0000-0000-000000000013", "kis")
        val databaseNow = currentDatabaseTime()
        val observationBase = databaseNow.minusSeconds(300)
        val retentionUntil = databaseNow.minusSeconds(1)
        val cleanupAt = databaseNow.plusSeconds(1)
        createFullRunningRun(
            scope = scope,
            retentionUntil = databaseNow.plusSeconds(3_600),
            baseTime = observationBase,
        )
        updateRetention(scope, retentionUntil)

        val cleanupService = ObservationCleanupService(
            repository = store,
            clock = Clock.fixed(cleanupAt, ZoneOffset.UTC),
        )
        val audit = cleanupService.cleanupExpired(limit = 100).single { it.scope == scope }

        assertThat(audit.result).isEqualTo(ObservationCleanupResult.DELETED)
        assertThat(audit.terminalState).isEqualTo(ObservationRunState.INVALID)
        assertThat(audit.expectedTickersDeleted).isEqualTo(1)
        assertThat(audit.ticksDeleted).isEqualTo(1)
        assertThat(store.findRun(scope)).isNull()
        assertThat(countCleanupAudits(audit.cleanupId)).isEqualTo(1)
    }

    @Test
    fun `cleanup refuses an application clock jump before database retention expiry`() {
        val scope = scope("51000000-0000-0000-0000-000000000021", "kis")
        val cleanupId = UUID.fromString("53000000-0000-0000-0000-000000000003")
        createFullRunningRun(scope)
        val applicationClockJump = FAR_FUTURE.plusSeconds(1)

        assertThat(store.findExpiredScopes(applicationClockJump, limit = 100))
            .doesNotContain(scope)

        val audit = store.cleanupTerminalRun(scope, cleanupId, applicationClockJump)

        assertThat(audit.result).isEqualTo(ObservationCleanupResult.SKIPPED_NOT_EXPIRED)
        assertThat(audit.terminalState).isNull()
        assertThat(audit.expectedTickersDeleted).isZero()
        assertThat(audit.clockSamplesDeleted).isZero()
        assertThat(audit.restPollsDeleted).isZero()
        assertThat(audit.ticksDeleted).isZero()
        assertThat(audit.candlesDeleted).isZero()
        assertThat(audit.faultEventsDeleted).isZero()
        assertThat(store.findRun(scope)).isNotNull()
        assertThat(store.evidenceSnapshot(scope)!!.tickCount).isEqualTo(1)
        assertThat(countCleanupAudits(cleanupId)).isEqualTo(1)
    }

    @Test
    fun `observation writes do not modify canonical minute candles`() {
        val guard = MinuteCandle(
            ticker = "OBS900001",
            bucketStart = Instant.parse("2026-08-17T00:00:00Z"),
            open = 100,
            high = 110,
            low = 90,
            close = 105,
            volume = 1_000,
            tradeCount = 10,
            revision = 0,
            isFinal = true,
            source = "observation-guard",
        )
        minuteCandleStore.save(guard)
        val before = minuteCandleStore.find(guard.ticker, guard.bucketStart, guard.bucketStart.plusSeconds(60))

        val scope = scope("51000000-0000-0000-0000-000000000014", "kis")
        createFullRunningRun(scope)

        assertThat(minuteCandleStore.find(guard.ticker, guard.bucketStart, guard.bucketStart.plusSeconds(60)))
            .containsExactlyElementsOf(before)
        assertThat(
            minuteCandleStore.find(
                "100001",
                ObservationTestFixtures.baseTime,
                ObservationTestFixtures.baseTime.plusSeconds(120),
            ),
        ).isEmpty()
    }

    private fun createFullRunningRun(
        scope: ObservationScope,
        retentionUntil: Instant = FAR_FUTURE,
        baseTime: Instant = ObservationTestFixtures.baseTime,
    ) {
        createRunningRun(scope, retentionUntil = retentionUntil, baseTime = baseTime)
        assertThat(
            store.appendRestPoll(
                ObservationTestFixtures.restPoll(scope).copy(
                    requestStartedAt = baseTime.plusSeconds(1),
                    observedAt = baseTime.plusSeconds(2),
                    normalizedAt = baseTime.plusSeconds(2),
                    requestedFrom = baseTime.plusSeconds(1),
                    requestedTo = baseTime.plusSeconds(120),
                ),
            ),
        )
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendTick(
                ObservationTestFixtures.tick(scope).copy(
                    providerOccurredAt = baseTime.plusSeconds(10),
                    socketReceivedAt = baseTime.plusSeconds(11),
                    normalizedAt = baseTime.plusSeconds(11),
                ),
            ),
        )
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendCandle(
                ObservationTestFixtures.candle(scope).copy(
                    bucketStart = baseTime.plusSeconds(30),
                    observedAt = baseTime.plusSeconds(59),
                ),
            ),
        )
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendFaultEvent(
                ObservationTestFixtures.faultEvent(scope).copy(
                    observedAt = baseTime.plusSeconds(30),
                    gapFrom = baseTime.plusSeconds(30),
                    gapTo = baseTime.plusSeconds(60),
                ),
            ),
        )
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = baseTime.plusSeconds(119),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)
    }

    private fun createRunningRun(
        scope: ObservationScope,
        retentionUntil: Instant = FAR_FUTURE,
        baseTime: Instant = ObservationTestFixtures.baseTime,
    ) {
        store.createRun(
            ObservationTestFixtures.run(
                scope = scope,
                retentionUntil = retentionUntil,
                createdAt = baseTime,
                windowStart = baseTime.plusSeconds(1),
                windowEnd = baseTime.plusSeconds(120),
            ),
        )
        store.createExpectedTickers(ObservationTestFixtures.expectedTickers(scope))
        store.createSemantics(ObservationTestFixtures.semantics(scope).copy(confirmedAt = baseTime))
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope).copy(sampledAt = baseTime),
            ),
        )
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.activateRun(
                scope,
                expectedClockSampleSequence = 1,
                changedAt = baseTime.plusSeconds(1),
            ),
        ).isTrue()
    }

    private fun updateRight(
        scope: ObservationScope,
        column: String,
        decision: RightsDecision,
    ) {
        require(column == "storage_right" || column == "benchmark_right")
        assertThat(
            jdbc.update(
                "UPDATE market_observation_runs SET $column = :decision WHERE run_id = :runId AND provider = :provider",
                MapSqlParameterSource()
                    .addValue("decision", decision.name)
                    .addValue("runId", scope.runId)
                    .addValue("provider", scope.provider),
            ),
        ).isEqualTo(1)
    }

    private fun updateRetention(
        scope: ObservationScope,
        retentionUntil: Instant,
    ) {
        assertThat(
            jdbc.update(
                """
                    UPDATE market_observation_runs
                    SET retention_until = :retentionUntil
                    WHERE run_id = :runId AND provider = :provider
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("retentionUntil", retentionUntil.atOffset(ZoneOffset.UTC))
                    .addValue("runId", scope.runId)
                    .addValue("provider", scope.provider),
            ),
        ).isEqualTo(1)
    }

    private fun countRows(
        table: String,
        scope: ObservationScope,
        condition: String? = null,
    ): Long {
        require(table in OBSERVATION_TABLES)
        val extraCondition = condition?.let { " AND $it" }.orEmpty()
        return jdbc.queryForObject(
            "SELECT count(*) FROM $table WHERE run_id = :runId AND provider = :provider$extraCondition",
            MapSqlParameterSource()
                .addValue("runId", scope.runId)
                .addValue("provider", scope.provider),
            Long::class.javaObjectType,
        )!!
    }

    private fun countCleanupAudits(cleanupId: UUID): Long = jdbc.queryForObject(
        "SELECT count(*) FROM market_observation_cleanup_audits WHERE cleanup_id = :cleanupId",
        MapSqlParameterSource("cleanupId", cleanupId),
        Long::class.javaObjectType,
    )!!

    private fun currentDatabaseTime(): Instant = jdbc.queryForObject(
        "SELECT CURRENT_TIMESTAMP",
        MapSqlParameterSource(),
        OffsetDateTime::class.java,
    )!!.toInstant()

    private fun scope(runId: String, provider: String) = ObservationTestFixtures.scope(
        runId = UUID.fromString(runId),
        provider = provider,
    )

    private companion object {
        val ACTIVATED_AT: Instant = ObservationTestFixtures.baseTime.plusSeconds(1)
        val FAR_FUTURE: Instant = Instant.parse("2099-01-01T00:00:00Z")

        val OBSERVATION_TABLES = setOf(
            "market_observation_ticks",
            "market_observation_rest_polls",
            "market_observation_candles",
        )
    }
}
