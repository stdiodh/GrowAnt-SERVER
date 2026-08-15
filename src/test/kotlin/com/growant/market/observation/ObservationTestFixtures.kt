package com.growant.market.observation

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal object ObservationTestFixtures {
    val baseTime: Instant = Instant.parse("2026-08-17T00:00:00Z")

    fun scope(
        runId: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001"),
        provider: String = "kis",
    ) = ObservationScope(runId, provider)

    fun rights(
        storage: RightsDecision = RightsDecision.ALLOWED,
        benchmark: RightsDecision = RightsDecision.ALLOWED,
        replay: RightsDecision = RightsDecision.ALLOWED,
        ci: RightsDecision = RightsDecision.ALLOWED,
        internalDisplay: RightsDecision = RightsDecision.ALLOWED,
        externalDistribution: RightsDecision = RightsDecision.DENIED,
    ) = ObservationRights(
        storage = storage,
        benchmark = benchmark,
        replay = replay,
        ci = ci,
        internalDisplay = internalDisplay,
        externalDistribution = externalDistribution,
        evidenceId = "synthetic-fixture-rights-v1",
        evidenceChecksumSha256 = "a".repeat(64),
    )

    fun run(
        scope: ObservationScope = scope(),
        state: ObservationRunState = ObservationRunState.PLANNED,
        rights: ObservationRights = rights(),
        retentionUntil: Instant = Instant.parse("2099-01-01T00:00:00Z"),
        createdAt: Instant = baseTime,
        windowStart: Instant = createdAt.plusSeconds(1),
        windowEnd: Instant = createdAt.plusSeconds(120),
        expectedTickerCount: Int = 1,
        tickerSetChecksumSha256: String = ObservationTickerSetChecksum.sha256(listOf("100001")),
        latestClockSampleSequence: Long? = when (state) {
            ObservationRunState.RUNNING,
            ObservationRunState.COMPLETED,
            -> 1
            ObservationRunState.PLANNED,
            ObservationRunState.INVALID,
            -> null
        },
        activationClockSampleSequence: Long? = when (state) {
            ObservationRunState.RUNNING,
            ObservationRunState.COMPLETED,
            -> 1
            ObservationRunState.PLANNED,
            ObservationRunState.INVALID,
            -> null
        },
        startedAt: Instant? = null,
        completedAt: Instant? = null,
    ) = ObservationRun(
        scope = scope,
        role = ObservationRole.REALTIME_POC,
        origin = ObservationOrigin.SYNTHETIC,
        state = state,
        rights = rights,
        benchmarkSpecId = "provider-benchmark-v1",
        benchmarkSpecChecksumSha256 = "c".repeat(64),
        sourceCommitSha = "d".repeat(40),
        sourceTreeDirty = false,
        windowStart = windowStart,
        windowEnd = windowEnd,
        expectedTickerCount = expectedTickerCount,
        tickerSetChecksumSha256 = tickerSetChecksumSha256,
        latestClockSampleSequence = latestClockSampleSequence,
        activationClockSampleSequence = activationClockSampleSequence,
        retentionUntil = retentionUntil,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
    )

    fun expectedTickers(
        scope: ObservationScope = scope(),
        tickers: List<String> = listOf("100001"),
    ): List<ObservationExpectedTicker> = tickers.mapIndexed { ordinal, ticker ->
        ObservationExpectedTicker(scope = scope, ticker = ticker, ordinal = ordinal)
    }

    fun semantics(scope: ObservationScope = scope()) = MarketDataSemantics(
        scope = scope,
        venue = MarketVenue.KRX,
        session = MarketSession.REGULAR,
        interval = CandleInterval.ONE_MINUTE,
        timestampOrigin = TimestampOrigin.PROVIDER_EVENT,
        timestampPrecisionMicros = 1_000,
        providerZoneId = ZoneId.of("Asia/Seoul"),
        candleTimeConvention = CandleTimeConvention.START,
        adjustmentMode = AdjustmentMode.UNADJUSTED,
        correctionPolicy = CorrectionPolicy.CANDLE_REVISION,
        emptyMinutePolicy = EmptyMinutePolicy.OMIT,
        volumeUnit = VolumeUnit.SHARES,
        providerEventIdScope = ProviderEventIdScope.GLOBAL,
        documentEvidenceId = "synthetic-semantics-v1",
        documentEvidenceChecksumSha256 = "b".repeat(64),
        confirmedAt = baseTime,
    )

    fun clockSample(
        scope: ObservationScope = scope(),
        sampleSequence: Long = 1,
    ) = ObservationClockSample(
        scope = scope,
        sampleSequence = sampleSequence,
        sampledAt = baseTime,
        localClockOffsetMicros = 20_000,
        uncertaintyMicros = 10_000,
        synchronized = true,
        source = ObservationClockSource.SYNTHETIC,
    )

    fun restPoll(
        scope: ObservationScope = scope(),
        requestId: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001"),
    ) = RestPollObservation(
        scope = scope,
        ticker = "100001",
        requestId = requestId,
        pollRunId = requestId,
        pageOrdinal = 0,
        requestCursor = null,
        nextCursor = null,
        pollTerminal = true,
        observedAt = baseTime.plusSeconds(2),
        requestStartedAt = baseTime.plusSeconds(1),
        normalizedAt = baseTime.plusSeconds(2),
        requestedFrom = baseTime.plusSeconds(1),
        requestedTo = baseTime.plusSeconds(120),
        outcome = RestPollOutcome.SUCCESS,
        httpStatus = 200,
        roundRobinPosition = 0,
        returnedCandleCount = 1,
    )

    fun tick(
        scope: ObservationScope = scope(),
        localReceiveSequence: Long = 1,
        providerEventId: String? = "provider-event-1",
    ) = TickObservation(
        scope = scope,
        expectedTicker = "100001",
        reportedTicker = "100001",
        connectionEpoch = UUID.fromString("30000000-0000-0000-0000-000000000001"),
        localReceiveSequence = localReceiveSequence,
        clockSampleSequence = 1,
        providerEventId = providerEventId,
        providerOccurredAt = baseTime.plusSeconds(10),
        socketReceivedAt = baseTime.plusSeconds(11),
        normalizedAt = baseTime.plusSeconds(11),
        price = 50_000,
        quantity = 10,
        outcome = TickObservationOutcome.ACCEPTED,
    )

    fun candle(
        scope: ObservationScope = scope(),
        observationSequence: Long = 1,
    ) = CandleObservation(
        scope = scope,
        ticker = "100001",
        bucketStart = baseTime.plusSeconds(30),
        observationSequence = observationSequence,
        providerRevision = "revision-1",
        observedAt = baseTime.plusSeconds(59),
        source = CandleObservationSource.PROVIDER_REST,
        open = 50_000,
        high = 50_200,
        low = 49_900,
        close = 50_100,
        volume = 1_000,
        tradeCount = 10,
        isFinal = true,
        adjusted = false,
        restRequestId = UUID.fromString("20000000-0000-0000-0000-000000000001"),
    )

    fun faultEvent(
        scope: ObservationScope = scope(),
        eventSequence: Long = 1,
    ) = FaultEvent(
        scope = scope,
        faultId = UUID.fromString("40000000-0000-0000-0000-000000000001"),
        eventSequence = eventSequence,
        type = FaultEventType.DISCONNECTED,
        observedAt = baseTime.plusSeconds(30),
        ticker = "100001",
        connectionEpoch = UUID.fromString("30000000-0000-0000-0000-000000000001"),
        gapFrom = baseTime.plusSeconds(30),
        gapTo = baseTime.plusSeconds(60),
    )
}
