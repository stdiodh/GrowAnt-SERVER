package com.growant.market.observation.export

import com.growant.market.observation.ObservationExpectedTicker
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationScope
import com.growant.market.observation.ObservationTickerSetChecksum
import com.growant.market.observation.port.MarketObservationRepository
import java.nio.file.Path
import java.time.Clock

class ObservationEvidenceExportService(
    private val repository: MarketObservationRepository,
    private val writer: ObservationEvidenceManifestWriter,
    private val clock: Clock,
) {
    fun export(
        scope: ObservationScope,
        outputFile: Path,
    ): Path {
        val bundle = repository.evidenceBundle(scope)
            ?: throw ObservationEvidenceUnavailableException("evidence bundle not found")
        val exportedAt = clock.instant()
        val run = bundle.run
        val snapshot = bundle.snapshot
        val semantics = bundle.semantics
        val activationClockSample = bundle.activationClockSample
        val expectedTickers = bundle.expectedTickers

        if (run.scope != scope) {
            throw ObservationEvidenceUnavailableException("run identity mismatch")
        }
        if (run.state != ObservationRunState.COMPLETED && run.state != ObservationRunState.INVALID) {
            throw ObservationEvidenceUnavailableException("run is not terminal")
        }
        if (!run.retentionUntil.isAfter(exportedAt)) {
            throw ObservationEvidenceUnavailableException("retention expired")
        }

        val completedAt = run.completedAt
            ?: throw ObservationEvidenceUnavailableException("terminal timestamp missing")
        if (snapshot.scope != scope || snapshot.state != run.state) {
            throw ObservationEvidenceUnavailableException("snapshot identity mismatch")
        }
        if (semantics != null && semantics.scope != scope) {
            throw ObservationEvidenceUnavailableException("semantics identity mismatch")
        }
        validateExpectedTickers(run.expectedTickerCount, run.tickerSetChecksumSha256, expectedTickers, scope, run.state)
        if (
            activationClockSample != null &&
            (activationClockSample.scope != scope ||
                activationClockSample.sampleSequence != run.activationClockSampleSequence)
        ) {
            throw ObservationEvidenceUnavailableException("activation clock identity mismatch")
        }
        if ((run.activationClockSampleSequence == null) != (activationClockSample == null)) {
            throw ObservationEvidenceUnavailableException("activation clock evidence is incomplete")
        }

        if (run.state == ObservationRunState.COMPLETED) {
            if (run.startedAt == null || semantics == null || activationClockSample == null) {
                throw ObservationEvidenceUnavailableException("completed run evidence is incomplete")
            }
        }

        return writer.write(
            outputFile = outputFile,
            manifest = ObservationEvidenceManifest(
                observationSchemaVersion = bundle.schemaVersion,
                runId = scope.runId,
                provider = scope.provider,
                role = run.role.name,
                origin = run.origin.name,
                state = run.state.name,
                benchmarkSpecId = run.benchmarkSpecId,
                benchmarkSpecChecksumSha256 = run.benchmarkSpecChecksumSha256,
                sourceCommitSha = run.sourceCommitSha,
                sourceTreeDirty = run.sourceTreeDirty,
                windowStart = run.windowStart,
                windowEnd = run.windowEnd,
                expectedTickerCount = run.expectedTickerCount,
                tickerSetChecksumSha256 = run.tickerSetChecksumSha256,
                tickersInOrdinalOrder = expectedTickers.map(ObservationExpectedTicker::ticker),
                startedAt = run.startedAt,
                completedAt = completedAt,
                exportedAt = exportedAt,
                retentionUntil = run.retentionUntil,
                activationClockSampleSequence = activationClockSample?.sampleSequence,
                clockSampledAt = activationClockSample?.sampledAt,
                localClockOffsetMicros = activationClockSample?.localClockOffsetMicros,
                clockUncertaintyMicros = activationClockSample?.uncertaintyMicros,
                clockSynchronized = activationClockSample?.synchronized,
                clockSource = activationClockSample?.source?.name,
                semantics = semantics,
                rights = run.rights,
                rowChecksumSha256 = snapshot.rowChecksumSha256.lowercase(),
                recordCounts = mapOf(
                    "candle" to snapshot.candleCount,
                    "clock-sample" to snapshot.clockSampleCount,
                    "expected-ticker" to expectedTickers.size.toLong(),
                    "fault-event" to snapshot.faultEventCount,
                    "rest-poll" to snapshot.restPollCount,
                    "tick" to snapshot.tickCount,
                ),
            ),
        )
    }

    private fun validateExpectedTickers(
        declaredCount: Int,
        declaredChecksumSha256: String,
        expectedTickers: List<ObservationExpectedTicker>,
        scope: ObservationScope,
        state: ObservationRunState,
    ) {
        if (expectedTickers.any { it.scope != scope }) {
            throw ObservationEvidenceUnavailableException("expected ticker identity mismatch")
        }
        if (expectedTickers.map(ObservationExpectedTicker::ordinal) != expectedTickers.indices.toList()) {
            throw ObservationEvidenceUnavailableException("expected ticker order is incomplete")
        }
        if (expectedTickers.size > declaredCount) {
            throw ObservationEvidenceUnavailableException("expected ticker count exceeds the run specification")
        }

        val completeTickerSet = expectedTickers.size == declaredCount
        if (state == ObservationRunState.COMPLETED && !completeTickerSet) {
            throw ObservationEvidenceUnavailableException("completed run ticker evidence is incomplete")
        }
        if (
            completeTickerSet &&
            runCatching {
                ObservationTickerSetChecksum.sha256(expectedTickers.map(ObservationExpectedTicker::ticker))
            }.getOrNull() != declaredChecksumSha256
        ) {
            throw ObservationEvidenceUnavailableException("expected ticker checksum mismatch")
        }
    }
}

class ObservationEvidenceUnavailableException(
    reason: String,
) : IllegalStateException("Observation evidence unavailable: $reason")
