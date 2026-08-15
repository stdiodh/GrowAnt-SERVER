package com.growant.market.observation

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class ObservationScope(
    val runId: UUID,
    val provider: String,
) {
    init {
        require(provider.matches(PROVIDER_PATTERN)) {
            "provider must contain only lowercase letters, numbers, '.', '_' or '-'"
        }
        require(provider.length <= PROVIDER_MAX_LENGTH) {
            "provider must not exceed $PROVIDER_MAX_LENGTH characters"
        }
    }

    private companion object {
        val PROVIDER_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
        const val PROVIDER_MAX_LENGTH = 40
    }
}

enum class ObservationRole {
    REALTIME_POC,
    CANDLE_REFERENCE,
    KOSPI_FEED,
}

enum class ObservationOrigin {
    PROVIDER,
    SYNTHETIC,
}

enum class ObservationRunState {
    PLANNED,
    RUNNING,
    COMPLETED,
    INVALID,
}

enum class RightsDecision {
    UNKNOWN,
    ALLOWED,
    DENIED,
}

data class ObservationRights(
    val storage: RightsDecision,
    val benchmark: RightsDecision,
    val replay: RightsDecision,
    val ci: RightsDecision,
    val internalDisplay: RightsDecision,
    val externalDistribution: RightsDecision,
    val evidenceId: String,
    val evidenceChecksumSha256: String,
) {
    init {
        require(evidenceId.matches(EVIDENCE_ID_PATTERN)) {
            "evidenceId must be a safe identifier"
        }
        require(evidenceId.length <= EVIDENCE_ID_MAX_LENGTH) {
            "evidenceId must not exceed $EVIDENCE_ID_MAX_LENGTH characters"
        }
        require(evidenceChecksumSha256.matches(SHA256_PATTERN)) {
            "evidenceChecksumSha256 must contain 64 hexadecimal characters"
        }
    }

    private companion object {
        val EVIDENCE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        const val EVIDENCE_ID_MAX_LENGTH = 120
    }
}

data class ObservationRun(
    val scope: ObservationScope,
    val role: ObservationRole,
    val origin: ObservationOrigin,
    val state: ObservationRunState,
    val rights: ObservationRights,
    val benchmarkSpecId: String,
    val benchmarkSpecChecksumSha256: String,
    val sourceCommitSha: String,
    val sourceTreeDirty: Boolean,
    val windowStart: Instant,
    val windowEnd: Instant,
    val expectedTickerCount: Int,
    val tickerSetChecksumSha256: String,
    val latestClockSampleSequence: Long? = null,
    val activationClockSampleSequence: Long? = null,
    val retentionUntil: Instant,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
) {
    init {
        require(benchmarkSpecId.matches(SAFE_ID_PATTERN)) { "benchmarkSpecId must be a safe identifier" }
        require(benchmarkSpecId.length <= SAFE_ID_MAX_LENGTH) {
            "benchmarkSpecId must not exceed $SAFE_ID_MAX_LENGTH characters"
        }
        require(benchmarkSpecChecksumSha256.matches(SHA256_PATTERN)) {
            "benchmarkSpecChecksumSha256 must contain 64 lowercase hexadecimal characters"
        }
        require(sourceCommitSha.matches(COMMIT_SHA_PATTERN)) {
            "sourceCommitSha must contain 40 or 64 lowercase hexadecimal characters"
        }
        require(windowStart < windowEnd) { "windowStart must be before windowEnd" }
        require(expectedTickerCount in 1..EXPECTED_TICKER_COUNT_MAX) {
            "expectedTickerCount must be between 1 and $EXPECTED_TICKER_COUNT_MAX"
        }
        require(tickerSetChecksumSha256.matches(SHA256_PATTERN)) {
            "tickerSetChecksumSha256 must contain 64 lowercase hexadecimal characters"
        }
        require(latestClockSampleSequence == null || latestClockSampleSequence >= 0) {
            "latestClockSampleSequence must not be negative"
        }
        require(activationClockSampleSequence == null || activationClockSampleSequence >= 0) {
            "activationClockSampleSequence must not be negative"
        }
        require(
            activationClockSampleSequence == null ||
                latestClockSampleSequence != null && latestClockSampleSequence >= activationClockSampleSequence,
        ) { "activation clock sample must not be newer than the latest clock sample" }
        require(state != ObservationRunState.PLANNED || activationClockSampleSequence == null) {
            "a planned run must not have an activation clock sample"
        }
        require(
            state !in setOf(ObservationRunState.RUNNING, ObservationRunState.COMPLETED) ||
                activationClockSampleSequence != null,
        ) { "a running or completed run must have an activation clock sample" }
        require(retentionUntil > createdAt) { "retentionUntil must be after createdAt" }
        require(retentionUntil > windowEnd) { "retentionUntil must be after windowEnd" }
        require(startedAt == null || startedAt >= createdAt) { "startedAt must not be before createdAt" }
        require(completedAt == null || completedAt >= (startedAt ?: createdAt)) {
            "completedAt must not be before the run started"
        }
        require(completedAt == null || state == ObservationRunState.INVALID || retentionUntil > completedAt) {
            "retentionUntil must be after a successful completion"
        }
        require(
            state != ObservationRunState.COMPLETED || completedAt != null && completedAt >= windowEnd,
        ) { "a completed run must cover its full observation window" }
        require(
            when (state) {
                ObservationRunState.PLANNED -> startedAt == null && completedAt == null
                ObservationRunState.RUNNING -> startedAt != null && completedAt == null
                ObservationRunState.COMPLETED -> startedAt != null && completedAt != null
                ObservationRunState.INVALID -> completedAt != null
            },
        ) { "run state and lifecycle timestamps are inconsistent" }
    }

    private companion object {
        val SAFE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        val COMMIT_SHA_PATTERN = Regex("(?:[a-f0-9]{40}|[a-f0-9]{64})")
        const val SAFE_ID_MAX_LENGTH = 120
        const val EXPECTED_TICKER_COUNT_MAX = 10_000
    }
}

data class ObservationExpectedTicker(
    val scope: ObservationScope,
    val ticker: String,
    val ordinal: Int,
) {
    init {
        requireTicker(ticker)
        require(ordinal >= 0) { "ordinal must not be negative" }
    }
}

object ObservationTickerSetChecksum {
    fun sha256(tickersInOrdinalOrder: List<String>): String {
        require(tickersInOrdinalOrder.isNotEmpty()) { "expected tickers must not be empty" }
        tickersInOrdinalOrder.forEach(::requireTicker)
        require(tickersInOrdinalOrder.distinct().size == tickersInOrdinalOrder.size) {
            "expected tickers must not contain duplicates"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        tickersInOrdinalOrder.forEach { ticker ->
            val bytes = ticker.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

enum class MarketVenue {
    UNKNOWN,
    KRX,
    NXT,
    INTEGRATED,
}

enum class MarketSession {
    UNKNOWN,
    REGULAR,
    PRE_MARKET,
    AFTER_HOURS,
}

enum class CandleInterval {
    UNKNOWN,
    ONE_MINUTE,
}

enum class TimestampOrigin {
    UNKNOWN,
    PROVIDER_EVENT,
    PROVIDER_CANDLE,
    SERVER_RECEIVE,
    NOT_APPLICABLE,
}

enum class CandleTimeConvention {
    UNKNOWN,
    START,
    END,
    NOT_APPLICABLE,
}

enum class AdjustmentMode {
    UNKNOWN,
    UNADJUSTED,
    ADJUSTED,
    NOT_APPLICABLE,
}

enum class CorrectionPolicy {
    UNKNOWN,
    EVENT_REVISION,
    CANDLE_REVISION,
    NOT_SUPPORTED,
    NOT_APPLICABLE,
}

enum class EmptyMinutePolicy {
    UNKNOWN,
    OMIT,
    CARRY_FORWARD_ZERO_VOLUME,
    EXPLICIT_ZERO_VOLUME,
    NOT_APPLICABLE,
}

enum class VolumeUnit {
    UNKNOWN,
    SHARES,
    LOTS,
    CONTRACTS,
    NOT_APPLICABLE,
}

enum class ProviderEventIdScope {
    UNKNOWN,
    NONE,
    CONNECTION,
    SYMBOL,
    GLOBAL,
    NOT_APPLICABLE,
}

data class MarketDataSemantics(
    val scope: ObservationScope,
    val venue: MarketVenue,
    val session: MarketSession,
    val interval: CandleInterval,
    val timestampOrigin: TimestampOrigin,
    val timestampPrecisionMicros: Long?,
    val providerZoneId: ZoneId?,
    val candleTimeConvention: CandleTimeConvention,
    val adjustmentMode: AdjustmentMode,
    val correctionPolicy: CorrectionPolicy,
    val emptyMinutePolicy: EmptyMinutePolicy,
    val volumeUnit: VolumeUnit,
    val providerEventIdScope: ProviderEventIdScope,
    val documentEvidenceId: String,
    val documentEvidenceChecksumSha256: String,
    val confirmedAt: Instant? = null,
) {
    init {
        require(timestampPrecisionMicros == null || timestampPrecisionMicros > 0) {
            "timestampPrecisionMicros must be positive when present"
        }
        require(documentEvidenceId.matches(EVIDENCE_ID_PATTERN)) {
            "documentEvidenceId must be a safe identifier"
        }
        require(documentEvidenceId.length <= EVIDENCE_ID_MAX_LENGTH) {
            "documentEvidenceId must not exceed $EVIDENCE_ID_MAX_LENGTH characters"
        }
        require(documentEvidenceChecksumSha256.matches(SHA256_PATTERN)) {
            "documentEvidenceChecksumSha256 must contain 64 hexadecimal characters"
        }
        require(
            confirmedAt == null ||
                (
                    venue != MarketVenue.UNKNOWN &&
                        session != MarketSession.UNKNOWN &&
                        interval != CandleInterval.UNKNOWN &&
                        timestampOrigin != TimestampOrigin.UNKNOWN &&
                        candleTimeConvention != CandleTimeConvention.UNKNOWN &&
                        adjustmentMode != AdjustmentMode.UNKNOWN &&
                        correctionPolicy != CorrectionPolicy.UNKNOWN &&
                        emptyMinutePolicy != EmptyMinutePolicy.UNKNOWN &&
                        volumeUnit != VolumeUnit.UNKNOWN &&
                        providerEventIdScope != ProviderEventIdScope.UNKNOWN
                ),
        ) { "confirmed semantics must not contain unknown values" }
    }

    private companion object {
        val EVIDENCE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        const val EVIDENCE_ID_MAX_LENGTH = 120
    }
}

enum class ObservationClockSource {
    CHRONY,
    NTP,
    SYSTEM,
    SYNTHETIC,
}

data class ObservationClockSample(
    val scope: ObservationScope,
    val sampleSequence: Long,
    val sampledAt: Instant,
    /** Local clock minus trusted reference clock, in microseconds. */
    val localClockOffsetMicros: Long,
    val uncertaintyMicros: Long,
    val synchronized: Boolean,
    val source: ObservationClockSource,
) {
    init {
        require(sampleSequence >= 0) { "sampleSequence must not be negative" }
        require(uncertaintyMicros >= 0) { "uncertaintyMicros must not be negative" }
    }
}

enum class RestPollOutcome {
    SUCCESS,
    HTTP_ERROR,
    NETWORK_ERROR,
    PARSE_ERROR,
}

data class RestPollObservation(
    val scope: ObservationScope,
    val ticker: String,
    val requestId: UUID,
    val observedAt: Instant,
    val requestStartedAt: Instant,
    val normalizedAt: Instant?,
    val requestedFrom: Instant,
    val requestedTo: Instant,
    val outcome: RestPollOutcome,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    val rateLimitRemaining: Long? = null,
    val rateLimitResetAt: Instant? = null,
    val roundRobinPosition: Int? = null,
    val returnedCandleCount: Int = 0,
    val eligibleCandleCount: Int = returnedCandleCount,
) {
    init {
        requireTicker(ticker)
        require(!observedAt.isBefore(requestStartedAt)) {
            "observedAt must not be before requestStartedAt"
        }
        require(normalizedAt == null || !normalizedAt.isBefore(observedAt)) {
            "normalizedAt must not be before observedAt"
        }
        require(requestedFrom < requestedTo) { "requestedFrom must be before requestedTo" }
        require(httpStatus == null || httpStatus in 100..599) { "httpStatus must be a valid HTTP status" }
        require(retryAfterMillis == null || retryAfterMillis >= 0) { "retryAfterMillis must not be negative" }
        require(rateLimitRemaining == null || rateLimitRemaining >= 0) { "rateLimitRemaining must not be negative" }
        require(roundRobinPosition == null || roundRobinPosition >= 0) {
            "roundRobinPosition must not be negative"
        }
        require(returnedCandleCount >= 0) { "returnedCandleCount must not be negative" }
        require(eligibleCandleCount in 0..returnedCandleCount) {
            "eligibleCandleCount must be between zero and returnedCandleCount"
        }
        require(
            outcome != RestPollOutcome.SUCCESS ||
                (httpStatus != null && httpStatus in 200..299 && normalizedAt != null),
        ) { "successful REST polls require a 2xx status and normalization timestamp" }
    }
}

enum class TickObservationOutcome {
    ACCEPTED,
    DUPLICATE,
    TOO_LATE,
    INVALID,
    MISROUTED,
}

data class TickObservation(
    val scope: ObservationScope,
    val expectedTicker: String,
    val reportedTicker: String?,
    val connectionEpoch: UUID,
    val localReceiveSequence: Long,
    val clockSampleSequence: Long?,
    val providerEventId: String?,
    val providerOccurredAt: Instant?,
    val socketReceivedAt: Instant,
    val normalizedAt: Instant?,
    val price: Int?,
    val quantity: Long?,
    val outcome: TickObservationOutcome,
) {
    init {
        requireTicker(expectedTicker)
        reportedTicker?.let(::requireTicker)
        require(localReceiveSequence >= 0) { "localReceiveSequence must not be negative" }
        require(clockSampleSequence == null || clockSampleSequence >= 0) {
            "clockSampleSequence must not be negative"
        }
        require(providerEventId == null || providerEventId.length <= PROVIDER_EVENT_ID_MAX_LENGTH) {
            "providerEventId must not exceed $PROVIDER_EVENT_ID_MAX_LENGTH characters"
        }
        require(price == null || price > 0) { "price must be positive when present" }
        require(quantity == null || quantity > 0) { "quantity must be positive when present" }
        require(normalizedAt == null || !normalizedAt.isBefore(socketReceivedAt)) {
            "normalizedAt must not be before socketReceivedAt"
        }
        if (outcome !in setOf(TickObservationOutcome.INVALID, TickObservationOutcome.MISROUTED)) {
            require(
                providerOccurredAt != null &&
                    clockSampleSequence != null &&
                    normalizedAt != null &&
                    price != null &&
                    quantity != null,
            ) { "normalized ticks require event time, clock sample, normalization, price and quantity" }
        }
        require(
            when (outcome) {
                TickObservationOutcome.ACCEPTED,
                TickObservationOutcome.DUPLICATE,
                TickObservationOutcome.TOO_LATE,
                -> reportedTicker == expectedTicker
                TickObservationOutcome.MISROUTED ->
                    reportedTicker != null && reportedTicker != expectedTicker
                TickObservationOutcome.INVALID -> true
            },
        ) { "reportedTicker is inconsistent with the tick outcome" }
    }

    private companion object {
        const val PROVIDER_EVENT_ID_MAX_LENGTH = 120
    }
}

enum class CandleObservationSource {
    PROVIDER_REST,
    LOCAL_AGGREGATE,
}

data class CandleObservation(
    val scope: ObservationScope,
    val ticker: String,
    val bucketStart: Instant,
    val observationSequence: Long,
    val providerRevision: String?,
    val observedAt: Instant,
    val source: CandleObservationSource,
    val open: Int,
    val high: Int,
    val low: Int,
    val close: Int,
    val volume: Long,
    val tradeCount: Long? = null,
    val isFinal: Boolean? = null,
    val adjusted: Boolean? = null,
    val restRequestId: UUID? = null,
) {
    init {
        requireTicker(ticker)
        require(observationSequence >= 0) { "observationSequence must not be negative" }
        require(!observedAt.isBefore(bucketStart)) { "observedAt must not be before bucketStart" }
        require(providerRevision == null || providerRevision.length <= PROVIDER_REVISION_MAX_LENGTH) {
            "providerRevision must not exceed $PROVIDER_REVISION_MAX_LENGTH characters"
        }
        require(open > 0 && high > 0 && low > 0 && close > 0) { "candle prices must be positive" }
        require(high >= open && high >= low && high >= close) { "high must contain all candle prices" }
        require(low <= open && low <= high && low <= close) { "low must contain all candle prices" }
        require(volume >= 0) { "volume must not be negative" }
        require(tradeCount == null || tradeCount >= 0) { "tradeCount must not be negative" }
        require(
            (source == CandleObservationSource.PROVIDER_REST && restRequestId != null) ||
                (source == CandleObservationSource.LOCAL_AGGREGATE && restRequestId == null),
        ) { "restRequestId must be present only for provider REST candles" }
    }

    private companion object {
        const val PROVIDER_REVISION_MAX_LENGTH = 120
    }
}

enum class FaultEventType {
    FAULT_INJECTED,
    DISCONNECTED,
    RECONNECT_STARTED,
    RECONNECTED,
    RESUBSCRIBE_STARTED,
    RESUBSCRIBED,
    BACKFILL_STARTED,
    BACKFILL_COMPLETED,
    RECOVERY_VERIFIED,
    RECOVERY_FAILED,
}

data class FaultEvent(
    val scope: ObservationScope,
    val faultId: UUID,
    val eventSequence: Long,
    val type: FaultEventType,
    val observedAt: Instant,
    val ticker: String? = null,
    val connectionEpoch: UUID? = null,
    val gapFrom: Instant? = null,
    val gapTo: Instant? = null,
    val httpStatus: Int? = null,
) {
    init {
        ticker?.let(::requireTicker)
        require(eventSequence >= 0) { "eventSequence must not be negative" }
        require((gapFrom == null) == (gapTo == null)) { "gapFrom and gapTo must be present together" }
        require(gapFrom == null || gapFrom < gapTo) { "gapFrom must be before gapTo" }
        require(httpStatus == null || httpStatus in 100..599) { "httpStatus must be a valid HTTP status" }
    }
}

data class ObservationEvidenceSnapshot(
    val scope: ObservationScope,
    val state: ObservationRunState,
    val clockSampleCount: Long,
    val restPollCount: Long,
    val tickCount: Long,
    val candleCount: Long,
    val faultEventCount: Long,
    val firstObservedAt: Instant?,
    val lastObservedAt: Instant?,
    val rowChecksumSha256: String,
) {
    init {
        require(listOf(clockSampleCount, restPollCount, tickCount, candleCount, faultEventCount).all { it >= 0 }) {
            "observation evidence counts must not be negative"
        }
        require((firstObservedAt == null) == (lastObservedAt == null)) {
            "firstObservedAt and lastObservedAt must be present together"
        }
        require(firstObservedAt == null || firstObservedAt <= lastObservedAt) {
            "firstObservedAt must not be after lastObservedAt"
        }
        require(rowChecksumSha256.matches(Regex("[a-f0-9]{64}"))) {
            "rowChecksumSha256 must contain 64 lowercase hexadecimal characters"
        }
    }
}

data class ObservationEvidenceBundle(
    val schemaVersion: Int,
    val run: ObservationRun,
    val semantics: MarketDataSemantics?,
    val activationClockSample: ObservationClockSample?,
    val expectedTickers: List<ObservationExpectedTicker>,
    val snapshot: ObservationEvidenceSnapshot,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(snapshot.scope == run.scope) { "snapshot scope must match the run scope" }
        require(snapshot.state == run.state) { "snapshot state must match the run state" }
        require(expectedTickers.all { it.scope == run.scope }) {
            "expected ticker scopes must match the run scope"
        }
        require(activationClockSample == null || activationClockSample.scope == run.scope) {
            "activation clock sample scope must match the run scope"
        }
    }
}

enum class ObservationCleanupResult {
    DELETED,
    SKIPPED_NOT_FOUND,
    SKIPPED_NOT_TERMINAL,
    SKIPPED_NOT_EXPIRED,
}

data class ObservationCleanupAudit(
    val cleanupId: UUID,
    val scope: ObservationScope,
    val result: ObservationCleanupResult,
    val terminalState: ObservationRunState?,
    val retentionUntil: Instant?,
    val requestedAt: Instant,
    val completedAt: Instant,
    val semanticsDeleted: Long,
    val expectedTickersDeleted: Long,
    val clockSamplesDeleted: Long,
    val restPollsDeleted: Long,
    val ticksDeleted: Long,
    val candlesDeleted: Long,
    val faultEventsDeleted: Long,
) {
    init {
        require(completedAt >= requestedAt) { "completedAt must not be before requestedAt" }
        require(
            listOf(
                semanticsDeleted,
                expectedTickersDeleted,
                clockSamplesDeleted,
                restPollsDeleted,
                ticksDeleted,
                candlesDeleted,
                faultEventsDeleted,
            ).all { it >= 0 },
        ) { "cleanup deletion counts must not be negative" }
    }
}

private fun requireTicker(ticker: String) {
    require(ticker.matches(Regex("[0-9]{6}"))) { "ticker must contain six digits" }
}
