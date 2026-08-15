package com.growant.market.observation.persistence

import com.growant.market.observation.CandleObservation
import com.growant.market.observation.CandleInterval
import com.growant.market.observation.CandleTimeConvention
import com.growant.market.observation.AdjustmentMode
import com.growant.market.observation.CorrectionPolicy
import com.growant.market.observation.EmptyMinutePolicy
import com.growant.market.observation.FaultEvent
import com.growant.market.observation.MarketDataSemantics
import com.growant.market.observation.MarketSession
import com.growant.market.observation.MarketVenue
import com.growant.market.observation.ObservationCleanupAudit
import com.growant.market.observation.ObservationCleanupResult
import com.growant.market.observation.ObservationClockSample
import com.growant.market.observation.ObservationClockSource
import com.growant.market.observation.ObservationEvidenceBundle
import com.growant.market.observation.ObservationEvidenceSnapshot
import com.growant.market.observation.ObservationExpectedTicker
import com.growant.market.observation.ObservationOrigin
import com.growant.market.observation.ObservationRights
import com.growant.market.observation.ObservationRole
import com.growant.market.observation.ObservationRun
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationScope
import com.growant.market.observation.ObservationTickerSetChecksum
import com.growant.market.observation.ProviderEventIdScope
import com.growant.market.observation.RestPollObservation
import com.growant.market.observation.RightsDecision
import com.growant.market.observation.TickObservation
import com.growant.market.observation.TimestampOrigin
import com.growant.market.observation.VolumeUnit
import com.growant.market.observation.policy.ObservationClockLimits
import com.growant.market.observation.port.MarketObservationRepository
import com.growant.market.observation.port.ObservationAppendResult
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Repository
class MarketObservationStore(
    private val jdbc: NamedParameterJdbcTemplate,
) : MarketObservationRepository {
    override fun createRun(run: ObservationRun) {
        jdbc.update(CREATE_RUN_SQL, run.parameters())
    }

    @Transactional(readOnly = true)
    override fun findRun(scope: ObservationScope): ObservationRun? = findRunInCurrentTransaction(scope)

    @Transactional
    override fun createExpectedTickers(expectedTickers: List<ObservationExpectedTicker>) {
        require(expectedTickers.isNotEmpty()) { "expectedTickers must not be empty" }
        val scope = expectedTickers.first().scope
        require(expectedTickers.all { it.scope == scope }) { "expectedTickers must share one scope" }

        val orderedTickers = expectedTickers.sortedBy(ObservationExpectedTicker::ordinal)
        require(orderedTickers.map(ObservationExpectedTicker::ordinal) == orderedTickers.indices.toList()) {
            "expected ticker ordinals must be contiguous from zero"
        }
        require(orderedTickers.map(ObservationExpectedTicker::ticker).distinct().size == orderedTickers.size) {
            "expectedTickers must not contain duplicate tickers"
        }

        val identity = jdbc.query(
            LOCK_PLANNED_RUN_FOR_EXPECTED_TICKERS_SQL,
            scope.parameters(),
        ) { resultSet, _ ->
            ExpectedTickerIdentity(
                count = resultSet.getInt("expected_ticker_count"),
                checksumSha256 = resultSet.getString("ticker_set_sha256"),
            )
        }.singleOrNull() ?: error("Expected tickers can only be created for a planned run")

        check(identity.count == orderedTickers.size) { "Expected ticker count does not match the run specification" }
        check(
            identity.checksumSha256 == ObservationTickerSetChecksum.sha256(
                orderedTickers.map(ObservationExpectedTicker::ticker),
            ),
        ) { "Expected ticker checksum does not match the run specification" }

        val inserted = jdbc.batchUpdate(
            CREATE_EXPECTED_TICKER_SQL,
            orderedTickers.map { it.parameters() }.toTypedArray(),
        ).sum()
        check(inserted == orderedTickers.size) { "Expected ticker insertion was incomplete" }
    }

    @Transactional(readOnly = true)
    override fun findExpectedTickers(scope: ObservationScope): List<ObservationExpectedTicker> =
        findExpectedTickersInCurrentTransaction(scope)

    @Transactional
    override fun lockRunForActivation(scope: ObservationScope): ObservationRun? = jdbc.query(
        LOCK_PLANNED_RUN_FOR_ACTIVATION_SQL,
        scope.parameters(),
    ) { resultSet, _ -> resultSet.toObservationRun() }.singleOrNull()

    @Transactional
    override fun activateRun(
        scope: ObservationScope,
        expectedClockSampleSequence: Long,
        changedAt: Instant,
    ): Boolean {
        require(expectedClockSampleSequence >= 0) { "expectedClockSampleSequence must not be negative" }
        val run = jdbc.query(
            LOCK_RUN_FOR_ACTIVATION_SQL,
            scope.parameters().addValue("changedAt", changedAt.atUtc()),
        ) { resultSet, _ -> resultSet.toObservationRun() }.singleOrNull() ?: return false

        if (run.rights.storage != RightsDecision.ALLOWED || run.rights.benchmark != RightsDecision.ALLOWED) {
            return false
        }
        if (findSemanticsInCurrentTransaction(scope) == null) return false

        val latestClockSample = latestClockSampleInCurrentTransaction(scope) ?: return false
        if (latestClockSample.sampleSequence != expectedClockSampleSequence) return false

        val expectedTickers = findExpectedTickersInCurrentTransaction(scope)
        if (expectedTickers.size != run.expectedTickerCount) return false
        if (expectedTickers.map(ObservationExpectedTicker::ordinal) != expectedTickers.indices.toList()) return false
        if (
            ObservationTickerSetChecksum.sha256(expectedTickers.map(ObservationExpectedTicker::ticker)) !=
            run.tickerSetChecksumSha256
        ) {
            return false
        }

        return jdbc.update(
            ACTIVATE_RUN_SQL,
            scope.parameters()
                .addValue("expectedClockSampleSequence", expectedClockSampleSequence)
                .addValue("changedAt", changedAt.atUtc())
                .withClockLimits(changedAt),
        ) == 1
    }

    @Transactional
    override fun lockRunForCompletion(scope: ObservationScope): ObservationRun? = jdbc.query(
        LOCK_RUNNING_RUN_FOR_COMPLETION_DECISION_SQL,
        scope.parameters(),
    ) { resultSet, _ -> resultSet.toObservationRun() }.singleOrNull()

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun compareAndSetRunState(
        scope: ObservationScope,
        expected: ObservationRunState,
        updated: ObservationRunState,
        changedAt: Instant,
    ): Boolean {
        val parameters = scope.parameters()
            .addValue("expected", expected.name)
            .addValue("updated", updated.name)
            .addValue("changedAt", changedAt.atUtc())
            .withClockLimits(changedAt)

        if (updated == ObservationRunState.COMPLETED) {
            val locked = jdbc.query(
                LOCK_RUN_FOR_COMPLETION_SQL,
                parameters,
            ) { resultSet, _ -> resultSet.getBoolean("locked") }.singleOrNull() ?: false
            if (!locked) return false

            val ready = jdbc.queryForObject(
                COMPLETION_READY_SQL,
                parameters,
                Boolean::class.javaObjectType,
            ) ?: false
            if (!ready) return false
        }

        return jdbc.update(TRANSITION_RUN_SQL, parameters) == 1
    }

    override fun createSemantics(semantics: MarketDataSemantics) {
        check(jdbc.update(CREATE_SEMANTICS_SQL, semantics.parameters()) == 1) {
            "Observation semantics can only be created for a planned run"
        }
    }

    @Transactional(readOnly = true)
    override fun findSemantics(scope: ObservationScope): MarketDataSemantics? =
        findSemanticsInCurrentTransaction(scope)

    @Transactional
    override fun appendClockSample(sample: ObservationClockSample): ObservationAppendResult {
        val cursor = jdbc.query(
            LOCK_RUN_FOR_CLOCK_APPEND_SQL,
            sample.scope.parameters(),
        ) { resultSet, _ ->
            ClockAppendCursor(
                sequence = resultSet.getNullableLong("latest_clock_sample_sequence"),
                sampledAt = resultSet.instant("latest_clock_sampled_at"),
            )
        }
            .singleOrNull() ?: return ObservationAppendResult.REJECTED

        if (cursor.sequence != null && sample.sampleSequence <= cursor.sequence) {
            return ObservationAppendResult.REJECTED
        }
        check(jdbc.update(INSERT_CLOCK_SAMPLE_SQL, sample.parameters()) == 1) {
            "Clock sample insertion was incomplete"
        }
        if (cursor.sampledAt != null && sample.sampledAt < cursor.sampledAt) {
            return ObservationAppendResult.APPENDED_CLOCK_REGRESSION
        }
        check(
            jdbc.update(
                UPDATE_LATEST_CLOCK_SAMPLE_SQL,
                sample.scope.parameters().addValue("sampleSequence", sample.sampleSequence),
            ) == 1,
        ) { "Latest clock sample pointer update was incomplete" }
        return ObservationAppendResult.APPENDED
    }

    @Transactional(readOnly = true)
    override fun findClockSample(
        scope: ObservationScope,
        sampleSequence: Long,
    ): ObservationClockSample? {
        require(sampleSequence >= 0) { "sampleSequence must not be negative" }
        return findClockSampleInCurrentTransaction(scope, sampleSequence)
    }

    @Transactional(readOnly = true)
    override fun latestClockSample(scope: ObservationScope): ObservationClockSample? =
        latestClockSampleInCurrentTransaction(scope)

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun appendRestPoll(observation: RestPollObservation): ObservationAppendResult = appendObservation(
        scope = observation.scope,
        sql = APPEND_REST_POLL_SQL,
        parameters = observation.parameters(),
    )

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun appendTick(observation: TickObservation): ObservationAppendResult = appendObservation(
        scope = observation.scope,
        sql = APPEND_TICK_SQL,
        parameters = observation.parameters(),
    )

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun appendCandle(observation: CandleObservation): ObservationAppendResult = appendObservation(
        scope = observation.scope,
        sql = APPEND_CANDLE_SQL,
        parameters = observation.parameters(),
    )

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun appendFaultEvent(event: FaultEvent): ObservationAppendResult = appendObservation(
        scope = event.scope,
        sql = APPEND_FAULT_EVENT_SQL,
        parameters = event.parameters(),
    )

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun evidenceSnapshot(scope: ObservationScope): ObservationEvidenceSnapshot? =
        evidenceSnapshotInCurrentTransaction(scope)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun evidenceBundle(scope: ObservationScope): ObservationEvidenceBundle? {
        val run = findRunInCurrentTransaction(scope) ?: return null
        val snapshot = evidenceSnapshotInCurrentTransaction(scope) ?: return null
        val activationClockSample = run.activationClockSampleSequence?.let { sequence ->
            findClockSampleInCurrentTransaction(scope, sequence)
        }
        return ObservationEvidenceBundle(
            schemaVersion = EVIDENCE_SCHEMA_VERSION,
            run = run,
            semantics = findSemanticsInCurrentTransaction(scope),
            activationClockSample = activationClockSample,
            expectedTickers = findExpectedTickersInCurrentTransaction(scope),
            snapshot = snapshot,
        )
    }

    private fun evidenceSnapshotInCurrentTransaction(scope: ObservationScope): ObservationEvidenceSnapshot? {
        val counts = jdbc.query(
            EVIDENCE_SNAPSHOT_SQL,
            scope.parameters(),
        ) { resultSet, _ -> resultSet.toSnapshotCounts() }.singleOrNull() ?: return null

        return ObservationEvidenceSnapshot(
            scope = scope,
            state = counts.state,
            clockSampleCount = counts.clockSampleCount,
            restPollCount = counts.restPollCount,
            restPollRunCount = counts.restPollRunCount,
            tickCount = counts.tickCount,
            candleCount = counts.candleCount,
            faultEventCount = counts.faultEventCount,
            firstObservedAt = counts.firstObservedAt,
            lastObservedAt = counts.lastObservedAt,
            rowChecksumSha256 = rowChecksumSha256(scope),
        )
    }

    @Transactional(readOnly = true)
    override fun findExpiredScopes(expiredAt: Instant, limit: Int): List<ObservationScope> {
        require(limit > 0) { "limit must be positive" }
        return jdbc.query(
            FIND_EXPIRED_SCOPES_SQL,
            MapSqlParameterSource()
                .addValue("expiredAt", expiredAt.atUtc())
                .addValue("limit", limit),
        ) { resultSet, _ ->
            ObservationScope(
                runId = resultSet.getObject("run_id", UUID::class.java),
                provider = resultSet.getString("provider"),
            )
        }
    }

    @Transactional
    override fun cleanupTerminalRun(
        scope: ObservationScope,
        cleanupId: UUID,
        requestedAt: Instant,
    ): ObservationCleanupAudit {
        val candidate = jdbc.query(
            LOCK_RUN_FOR_CLEANUP_SQL,
            scope.parameters(),
        ) { resultSet, _ ->
            CleanupCandidate(
                state = ObservationRunState.valueOf(resultSet.getString("state")),
                retentionUntil = resultSet.instant("retention_until")!!,
                databaseNow = resultSet.instant("database_now")!!,
            )
        }.singleOrNull()

        val audit = when {
            candidate == null -> emptyCleanupAudit(
                cleanupId = cleanupId,
                scope = scope,
                result = ObservationCleanupResult.SKIPPED_NOT_FOUND,
                requestedAt = requestedAt,
            )
            candidate.retentionUntil > requestedAt || candidate.retentionUntil > candidate.databaseNow ->
                emptyCleanupAudit(
                    cleanupId = cleanupId,
                    scope = scope,
                    result = ObservationCleanupResult.SKIPPED_NOT_EXPIRED,
                    requestedAt = requestedAt,
                    terminalState = candidate.state.takeIf(TERMINAL_STATES::contains),
                    retentionUntil = candidate.retentionUntil,
                )
            else -> {
                val terminalCandidate = if (candidate.state in TERMINAL_STATES) {
                    candidate
                } else {
                    check(
                        jdbc.update(
                            INVALIDATE_EXPIRED_RUN_SQL,
                            scope.parameters().addValue("requestedAt", requestedAt.atUtc()),
                        ) == 1,
                    ) { "Locked expired observation run could not be invalidated" }
                    candidate.copy(state = ObservationRunState.INVALID)
                }
                deleteRunEvidence(
                    cleanupId = cleanupId,
                    scope = scope,
                    requestedAt = requestedAt,
                    candidate = terminalCandidate,
                )
            }
        }

        jdbc.update(INSERT_CLEANUP_AUDIT_SQL, audit.parameters())
        return audit
    }

    private fun findRunInCurrentTransaction(scope: ObservationScope): ObservationRun? = jdbc.query(
        FIND_RUN_SQL,
        scope.parameters(),
    ) { resultSet, _ -> resultSet.toObservationRun() }.singleOrNull()

    private fun findSemanticsInCurrentTransaction(scope: ObservationScope): MarketDataSemantics? = jdbc.query(
        FIND_SEMANTICS_SQL,
        scope.parameters(),
    ) { resultSet, _ -> resultSet.toMarketDataSemantics() }.singleOrNull()

    private fun findExpectedTickersInCurrentTransaction(
        scope: ObservationScope,
    ): List<ObservationExpectedTicker> = jdbc.query(
        FIND_EXPECTED_TICKERS_SQL,
        scope.parameters(),
    ) { resultSet, _ ->
        ObservationExpectedTicker(
            scope = scope,
            ticker = resultSet.getString("ticker"),
            ordinal = resultSet.getInt("ordinal"),
        )
    }

    private fun findClockSampleInCurrentTransaction(
        scope: ObservationScope,
        sampleSequence: Long,
    ): ObservationClockSample? = jdbc.query(
        FIND_CLOCK_SAMPLE_SQL,
        scope.parameters().addValue("sampleSequence", sampleSequence),
    ) { resultSet, _ -> resultSet.toObservationClockSample() }.singleOrNull()

    private fun latestClockSampleInCurrentTransaction(
        scope: ObservationScope,
    ): ObservationClockSample? = jdbc.query(
        LATEST_CLOCK_SAMPLE_SQL,
        scope.parameters(),
    ) { resultSet, _ -> resultSet.toObservationClockSample() }.singleOrNull()

    private fun rowChecksumSha256(scope: ObservationScope): String {
        val digest = MessageDigest.getInstance("SHA-256")
        CANONICAL_ROW_QUERIES.forEach { query ->
            jdbc.query(
                query.sql,
                scope.parameters(),
                ResultSetExtractor { resultSet ->
                    while (resultSet.next()) {
                        digest.appendFrame(query.type)
                        for (columnIndex in 1..resultSet.metaData.columnCount) {
                            digest.appendFrame(resultSet.canonicalValue(columnIndex))
                        }
                    }
                },
            )
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun ResultSet.canonicalValue(columnIndex: Int): String? = when (val value = getObject(columnIndex)) {
        null -> null
        is OffsetDateTime -> value.toInstant().toString()
        is Timestamp -> value.toInstant().toString()
        else -> value.toString()
    }

    private fun MessageDigest.appendFrame(value: String?) {
        if (value == null) {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(-1).array())
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    private fun deleteRunEvidence(
        cleanupId: UUID,
        scope: ObservationScope,
        requestedAt: Instant,
        candidate: CleanupCandidate,
    ): ObservationCleanupAudit {
        val parameters = scope.parameters()
        val candlesDeleted = jdbc.update(DELETE_CANDLES_SQL, parameters).toLong()
        val restPollsDeleted = jdbc.update(DELETE_REST_POLLS_SQL, parameters).toLong()
        val ticksDeleted = jdbc.update(DELETE_TICKS_SQL, parameters).toLong()
        val faultEventsDeleted = jdbc.update(DELETE_FAULT_EVENTS_SQL, parameters).toLong()
        val clockSamplesDeleted = jdbc.update(DELETE_CLOCK_SAMPLES_SQL, parameters).toLong()
        val semanticsDeleted = jdbc.update(DELETE_SEMANTICS_SQL, parameters).toLong()
        val expectedTickersDeleted = jdbc.update(DELETE_EXPECTED_TICKERS_SQL, parameters).toLong()
        check(jdbc.update(DELETE_RUN_SQL, parameters) == 1) { "Locked observation run disappeared during cleanup" }

        return ObservationCleanupAudit(
            cleanupId = cleanupId,
            scope = scope,
            result = ObservationCleanupResult.DELETED,
            terminalState = candidate.state,
            retentionUntil = candidate.retentionUntil,
            requestedAt = requestedAt,
            completedAt = requestedAt,
            semanticsDeleted = semanticsDeleted,
            expectedTickersDeleted = expectedTickersDeleted,
            clockSamplesDeleted = clockSamplesDeleted,
            restPollsDeleted = restPollsDeleted,
            ticksDeleted = ticksDeleted,
            candlesDeleted = candlesDeleted,
            faultEventsDeleted = faultEventsDeleted,
        )
    }

    private fun emptyCleanupAudit(
        cleanupId: UUID,
        scope: ObservationScope,
        result: ObservationCleanupResult,
        requestedAt: Instant,
        terminalState: ObservationRunState? = null,
        retentionUntil: Instant? = null,
    ) = ObservationCleanupAudit(
        cleanupId = cleanupId,
        scope = scope,
        result = result,
        terminalState = terminalState,
        retentionUntil = retentionUntil,
        requestedAt = requestedAt,
        completedAt = requestedAt,
        semanticsDeleted = 0,
        expectedTickersDeleted = 0,
        clockSamplesDeleted = 0,
        restPollsDeleted = 0,
        ticksDeleted = 0,
        candlesDeleted = 0,
        faultEventsDeleted = 0,
    )

    private fun ResultSet.toObservationRun(): ObservationRun = ObservationRun(
        scope = ObservationScope(
            runId = getObject("run_id", UUID::class.java),
            provider = getString("provider"),
        ),
        role = ObservationRole.valueOf(getString("role")),
        origin = ObservationOrigin.valueOf(getString("origin")),
        state = ObservationRunState.valueOf(getString("state")),
        rights = ObservationRights(
            storage = RightsDecision.valueOf(getString("storage_right")),
            benchmark = RightsDecision.valueOf(getString("benchmark_right")),
            replay = RightsDecision.valueOf(getString("replay_right")),
            ci = RightsDecision.valueOf(getString("ci_right")),
            internalDisplay = RightsDecision.valueOf(getString("internal_display_right")),
            externalDistribution = RightsDecision.valueOf(getString("external_distribution_right")),
            evidenceId = getString("rights_evidence_id"),
            evidenceChecksumSha256 = getString("rights_evidence_sha256"),
        ),
        benchmarkSpecId = getString("benchmark_spec_id"),
        benchmarkSpecChecksumSha256 = getString("benchmark_spec_sha256"),
        sourceCommitSha = getString("source_commit_sha"),
        sourceTreeDirty = getBoolean("source_tree_dirty"),
        windowStart = instant("window_start")!!,
        windowEnd = instant("window_end")!!,
        expectedTickerCount = getInt("expected_ticker_count"),
        tickerSetChecksumSha256 = getString("ticker_set_sha256"),
        latestClockSampleSequence = getNullableLong("latest_clock_sample_sequence"),
        activationClockSampleSequence = getNullableLong("activation_clock_sample_sequence"),
        retentionUntil = instant("retention_until")!!,
        createdAt = instant("created_at")!!,
        startedAt = instant("started_at"),
        completedAt = instant("completed_at"),
    )

    private fun ResultSet.toMarketDataSemantics(): MarketDataSemantics = MarketDataSemantics(
        scope = ObservationScope(
            runId = getObject("run_id", UUID::class.java),
            provider = getString("provider"),
        ),
        venue = MarketVenue.valueOf(getString("venue")),
        session = MarketSession.valueOf(getString("session")),
        interval = CandleInterval.valueOf(getString("candle_interval")),
        timestampOrigin = TimestampOrigin.valueOf(getString("timestamp_origin")),
        timestampPrecisionMicros = getNullableLong("timestamp_precision_micros"),
        providerZoneId = getString("provider_zone_id")?.let(ZoneId::of),
        candleTimeConvention = CandleTimeConvention.valueOf(getString("candle_time_convention")),
        adjustmentMode = AdjustmentMode.valueOf(getString("adjustment_mode")),
        correctionPolicy = CorrectionPolicy.valueOf(getString("correction_policy")),
        emptyMinutePolicy = EmptyMinutePolicy.valueOf(getString("empty_minute_policy")),
        volumeUnit = VolumeUnit.valueOf(getString("volume_unit")),
        providerEventIdScope = ProviderEventIdScope.valueOf(getString("provider_event_id_scope")),
        documentEvidenceId = getString("document_evidence_id"),
        documentEvidenceChecksumSha256 = getString("document_evidence_sha256"),
        confirmedAt = instant("confirmed_at"),
    )

    private fun ResultSet.toObservationClockSample(): ObservationClockSample = ObservationClockSample(
        scope = ObservationScope(
            runId = getObject("run_id", UUID::class.java),
            provider = getString("provider"),
        ),
        sampleSequence = getLong("sample_sequence"),
        sampledAt = instant("sampled_at")!!,
        localClockOffsetMicros = getLong("local_clock_offset_micros"),
        uncertaintyMicros = getLong("uncertainty_micros"),
        synchronized = getBoolean("is_synchronized"),
        source = ObservationClockSource.valueOf(getString("clock_source")),
    )

    private fun ResultSet.toSnapshotCounts(): SnapshotCounts = SnapshotCounts(
        state = ObservationRunState.valueOf(getString("state")),
        clockSampleCount = getLong("clock_sample_count"),
        restPollCount = getLong("rest_poll_count"),
        restPollRunCount = getLong("rest_poll_run_count"),
        tickCount = getLong("tick_count"),
        candleCount = getLong("candle_count"),
        faultEventCount = getLong("fault_event_count"),
        firstObservedAt = instant("first_observed_at"),
        lastObservedAt = instant("last_observed_at"),
    )

    private fun ResultSet.instant(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.getNullableLong(column: String): Long? =
        getLong(column).let { value -> if (wasNull()) null else value }

    private fun ObservationCleanupAudit.parameters() = scope.parameters()
        .addValue("cleanupId", cleanupId)
        .addValue("result", result.name)
        .addValue("terminalState", terminalState?.name)
        .addValue("retentionUntil", retentionUntil?.atUtc())
        .addValue("requestedAt", requestedAt.atUtc())
        .addValue("completedAt", completedAt.atUtc())
        .addValue("semanticsDeleted", semanticsDeleted)
        .addValue("expectedTickersDeleted", expectedTickersDeleted)
        .addValue("clockSamplesDeleted", clockSamplesDeleted)
        .addValue("restPollsDeleted", restPollsDeleted)
        .addValue("ticksDeleted", ticksDeleted)
        .addValue("candlesDeleted", candlesDeleted)
        .addValue("faultEventsDeleted", faultEventsDeleted)

    private fun appendResult(updatedRows: Int): ObservationAppendResult = when (updatedRows) {
        1 -> ObservationAppendResult.APPENDED
        0 -> ObservationAppendResult.REJECTED
        else -> error("Expected at most one observation row, but inserted $updatedRows")
    }

    private fun appendObservation(
        scope: ObservationScope,
        sql: String,
        parameters: MapSqlParameterSource,
    ): ObservationAppendResult {
        val locked = jdbc.query(
            LOCK_RUN_FOR_OBSERVATION_APPEND_SQL,
            scope.parameters(),
        ) { resultSet, _ -> resultSet.getBoolean("locked") }.singleOrNull() ?: false
        if (!locked) return ObservationAppendResult.REJECTED

        return appendResult(jdbc.update(sql, parameters))
    }

    private fun ObservationScope.parameters() = MapSqlParameterSource()
        .addValue("runId", runId)
        .addValue("provider", provider)

    private fun ObservationRun.parameters() = scope.parameters()
        .addValue("role", role.name)
        .addValue("origin", origin.name)
        .addValue("state", state.name)
        .addValue("storageRight", rights.storage.name)
        .addValue("benchmarkRight", rights.benchmark.name)
        .addValue("replayRight", rights.replay.name)
        .addValue("ciRight", rights.ci.name)
        .addValue("internalDisplayRight", rights.internalDisplay.name)
        .addValue("externalDistributionRight", rights.externalDistribution.name)
        .addValue("rightsEvidenceId", rights.evidenceId)
        .addValue("rightsEvidenceSha256", rights.evidenceChecksumSha256)
        .addValue("benchmarkSpecId", benchmarkSpecId)
        .addValue("benchmarkSpecSha256", benchmarkSpecChecksumSha256)
        .addValue("sourceCommitSha", sourceCommitSha)
        .addValue("sourceTreeDirty", sourceTreeDirty)
        .addValue("windowStart", windowStart.atUtc())
        .addValue("windowEnd", windowEnd.atUtc())
        .addValue("expectedTickerCount", expectedTickerCount)
        .addValue("tickerSetSha256", tickerSetChecksumSha256)
        .addValue("latestClockSampleSequence", latestClockSampleSequence)
        .addValue("activationClockSampleSequence", activationClockSampleSequence)
        .addValue("retentionUntil", retentionUntil.atUtc())
        .addValue("createdAt", createdAt.atUtc())
        .addValue("startedAt", startedAt?.atUtc())
        .addValue("completedAt", completedAt?.atUtc())

    private fun ObservationExpectedTicker.parameters() = scope.parameters()
        .addValue("ticker", ticker)
        .addValue("ordinal", ordinal)

    private fun MarketDataSemantics.parameters() = scope.parameters()
        .addValue("venue", venue.name)
        .addValue("session", session.name)
        .addValue("candleInterval", interval.name)
        .addValue("timestampOrigin", timestampOrigin.name)
        .addValue("timestampPrecisionMicros", timestampPrecisionMicros)
        .addValue("providerZoneId", providerZoneId?.id)
        .addValue("candleTimeConvention", candleTimeConvention.name)
        .addValue("adjustmentMode", adjustmentMode.name)
        .addValue("correctionPolicy", correctionPolicy.name)
        .addValue("emptyMinutePolicy", emptyMinutePolicy.name)
        .addValue("volumeUnit", volumeUnit.name)
        .addValue("providerEventIdScope", providerEventIdScope.name)
        .addValue("documentEvidenceId", documentEvidenceId)
        .addValue("documentEvidenceSha256", documentEvidenceChecksumSha256)
        .addValue("confirmedAt", confirmedAt?.atUtc())

    private fun ObservationClockSample.parameters() = scope.parameters()
        .addValue("sampleSequence", sampleSequence)
        .addValue("sampledAt", sampledAt.atUtc())
        .addValue("localClockOffsetMicros", localClockOffsetMicros)
        .addValue("uncertaintyMicros", uncertaintyMicros)
        .addValue("isSynchronized", synchronized)
        .addValue("clockSource", source.name)

    private fun RestPollObservation.parameters() = scope.parameters()
        .addValue("ticker", ticker)
        .addValue("requestId", requestId)
        .addValue("pollRunId", pollRunId)
        .addValue("pageOrdinal", pageOrdinal)
        .addValue("requestCursor", requestCursor)
        .addValue("nextCursor", nextCursor)
        .addValue("pollTerminal", pollTerminal)
        .addValue("observedAt", observedAt.atUtc())
        .addValue("requestStartedAt", requestStartedAt.atUtc())
        .addValue("normalizedAt", normalizedAt?.atUtc())
        .addValue("requestedFrom", requestedFrom.atUtc())
        .addValue("requestedTo", requestedTo.atUtc())
        .addValue("outcome", outcome.name)
        .addValue("httpStatus", httpStatus)
        .addValue("retryAfterMillis", retryAfterMillis)
        .addValue("rateLimitRemaining", rateLimitRemaining)
        .addValue("rateLimitResetAt", rateLimitResetAt?.atUtc())
        .addValue("roundRobinPosition", roundRobinPosition)
        .addValue("returnedCandleCount", returnedCandleCount)
        .addValue("eligibleCandleCount", eligibleCandleCount)
        .withClockLimits(requestStartedAt)

    private fun TickObservation.parameters() = scope.parameters()
        .addValue("expectedTicker", expectedTicker)
        .addValue("reportedTicker", reportedTicker)
        .addValue("connectionEpoch", connectionEpoch)
        .addValue("localReceiveSequence", localReceiveSequence)
        .addValue("clockSampleSequence", clockSampleSequence)
        .addValue("providerEventId", providerEventId)
        .addValue("providerOccurredAt", providerOccurredAt?.atUtc())
        .addValue("socketReceivedAt", socketReceivedAt.atUtc())
        .addValue("normalizedAt", normalizedAt?.atUtc())
        .addValue("price", price)
        .addValue("quantity", quantity)
        .addValue("outcome", outcome.name)
        .withClockLimits(socketReceivedAt)

    private fun CandleObservation.parameters() = scope.parameters()
        .addValue("ticker", ticker)
        .addValue("bucketStart", bucketStart.atUtc())
        .addValue("observationSequence", observationSequence)
        .addValue("providerRevision", providerRevision)
        .addValue("observedAt", observedAt.atUtc())
        .addValue("source", source.name)
        .addValue("open", open)
        .addValue("high", high)
        .addValue("low", low)
        .addValue("close", close)
        .addValue("volume", volume)
        .addValue("tradeCount", tradeCount)
        .addValue("isFinal", isFinal)
        .addValue("adjusted", adjusted)
        .addValue("restRequestId", restRequestId)
        .withClockLimits(observedAt)

    private fun FaultEvent.parameters() = scope.parameters()
        .addValue("faultId", faultId)
        .addValue("eventSequence", eventSequence)
        .addValue("eventType", type.name)
        .addValue("observedAt", observedAt.atUtc())
        .addValue("ticker", ticker)
        .addValue("connectionEpoch", connectionEpoch)
        .addValue("gapFrom", gapFrom?.atUtc())
        .addValue("gapTo", gapTo?.atUtc())
        .addValue("httpStatus", httpStatus)
        .withClockLimits(observedAt)

    private fun MapSqlParameterSource.withClockLimits(observedAt: Instant) =
        addValue("observationClockAt", observedAt.atUtc())
            .addValue("maximumClockSampleAgeMillis", ObservationClockLimits.maximumSampleAge.toMillis())
            .addValue(
                "maximumClockErrorMicros",
                ObservationClockLimits.maximumWorstCaseError.toNanos() / 1_000,
            )

    private fun Instant.atUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private data class SnapshotCounts(
        val state: ObservationRunState,
        val clockSampleCount: Long,
        val restPollCount: Long,
        val restPollRunCount: Long,
        val tickCount: Long,
        val candleCount: Long,
        val faultEventCount: Long,
        val firstObservedAt: Instant?,
        val lastObservedAt: Instant?,
    )

    private data class CleanupCandidate(
        val state: ObservationRunState,
        val retentionUntil: Instant,
        val databaseNow: Instant,
    )

    private data class ExpectedTickerIdentity(
        val count: Int,
        val checksumSha256: String,
    )

    private data class ClockAppendCursor(
        val sequence: Long?,
        val sampledAt: Instant?,
    )

    private data class CanonicalRowQuery(
        val type: String,
        val sql: String,
    )

    private companion object {
        const val EVIDENCE_SCHEMA_VERSION = 4

        const val AS_OF_CLOCK_SAMPLE_SEQUENCE_SQL = """
            (
                SELECT ac.sample_sequence
                FROM market_observation_clock_samples ac
                WHERE ac.run_id = r.run_id
                  AND ac.provider = r.provider
                  AND ac.sampled_at <= :observationClockAt
                ORDER BY ac.sampled_at DESC, ac.sample_sequence DESC
                LIMIT 1
            )
        """

        const val HEALTHY_AS_OF_CLOCK_SQL = """
            EXISTS (
                SELECT 1
                FROM market_observation_clock_samples hc
                WHERE hc.run_id = r.run_id
                  AND hc.provider = r.provider
                  AND hc.sample_sequence = $AS_OF_CLOCK_SAMPLE_SEQUENCE_SQL
                  AND hc.sampled_at <= :observationClockAt
                  AND hc.sampled_at >= :observationClockAt
                      - (:maximumClockSampleAgeMillis * INTERVAL '1 millisecond')
                  AND hc.is_synchronized = TRUE
                  AND (
                      r.origin <> 'PROVIDER'
                      OR hc.clock_source IN ('CHRONY', 'NTP')
                  )
                  AND abs(hc.local_clock_offset_micros::numeric) + hc.uncertainty_micros
                      <= :maximumClockErrorMicros
            )
        """

        const val INCOMPLETE_REST_EVIDENCE_SQL = """
            (
                EXISTS (
                    SELECT 1
                    FROM market_observation_rest_polls rp
                    WHERE rp.run_id = r.run_id
                      AND rp.provider = r.provider
                      AND rp.outcome = 'SUCCESS'
                      AND rp.eligible_candle_count <> (
                          SELECT count(*)
                          FROM market_observation_candles rc
                          WHERE rc.run_id = rp.run_id
                            AND rc.provider = rp.provider
                            AND rc.rest_request_id = rp.request_id
                            AND rc.source = 'PROVIDER_REST'
                      )
                )
                OR EXISTS (
                    SELECT 1
                    FROM market_observation_rest_polls rp
                    WHERE rp.run_id = r.run_id
                      AND rp.provider = r.provider
                    GROUP BY rp.ticker, rp.poll_run_id
                    HAVING count(*) FILTER (WHERE rp.poll_terminal) <> 1
                        OR max(rp.page_ordinal) FILTER (WHERE rp.poll_terminal) <> max(rp.page_ordinal)
                        OR min(rp.page_ordinal) <> 0
                        OR count(*) <> max(rp.page_ordinal) + 1
                )
                OR EXISTS (
                    SELECT 1
                    FROM market_observation_rest_polls current_page
                    WHERE current_page.run_id = r.run_id
                      AND current_page.provider = r.provider
                      AND current_page.page_ordinal > 0
                      AND NOT EXISTS (
                          SELECT 1
                          FROM market_observation_rest_polls previous_page
                          WHERE previous_page.run_id = current_page.run_id
                            AND previous_page.provider = current_page.provider
                            AND previous_page.ticker = current_page.ticker
                            AND previous_page.poll_run_id = current_page.poll_run_id
                            AND previous_page.page_ordinal = current_page.page_ordinal - 1
                            AND previous_page.outcome = 'SUCCESS'
                            AND previous_page.poll_terminal = FALSE
                            AND previous_page.next_cursor = current_page.request_cursor
                            AND previous_page.requested_from = current_page.requested_from
                            AND previous_page.requested_to = current_page.requested_to
                            AND previous_page.normalized_at <= current_page.request_started_at
                      )
                )
            )
        """

        const val CREATE_RUN_SQL = """
            INSERT INTO market_observation_runs (
                run_id, provider, role, origin, state,
                storage_right, benchmark_right, replay_right, ci_right,
                internal_display_right, external_distribution_right,
                rights_evidence_id, rights_evidence_sha256,
                benchmark_spec_id, benchmark_spec_sha256,
                source_commit_sha, source_tree_dirty, window_start, window_end,
                expected_ticker_count, ticker_set_sha256,
                latest_clock_sample_sequence, activation_clock_sample_sequence,
                retention_until, created_at, started_at, completed_at
            ) VALUES (
                :runId, :provider, :role, :origin, :state,
                :storageRight, :benchmarkRight, :replayRight, :ciRight,
                :internalDisplayRight, :externalDistributionRight,
                :rightsEvidenceId, :rightsEvidenceSha256,
                :benchmarkSpecId, :benchmarkSpecSha256,
                :sourceCommitSha, :sourceTreeDirty, :windowStart, :windowEnd,
                :expectedTickerCount, :tickerSetSha256,
                :latestClockSampleSequence, :activationClockSampleSequence,
                :retentionUntil, :createdAt, :startedAt, :completedAt
            )
        """

        const val FIND_RUN_SQL = """
            SELECT * FROM market_observation_runs
            WHERE run_id = :runId AND provider = :provider
        """

        const val LOCK_PLANNED_RUN_FOR_EXPECTED_TICKERS_SQL = """
            SELECT expected_ticker_count, ticker_set_sha256
            FROM market_observation_runs
            WHERE run_id = :runId
              AND provider = :provider
              AND state = 'PLANNED'
              AND retention_until > CURRENT_TIMESTAMP
            FOR SHARE
        """

        const val CREATE_EXPECTED_TICKER_SQL = """
            INSERT INTO market_observation_expected_tickers (run_id, provider, ticker, ordinal)
            VALUES (:runId, :provider, :ticker, :ordinal)
        """

        const val FIND_EXPECTED_TICKERS_SQL = """
            SELECT ticker, ordinal
            FROM market_observation_expected_tickers
            WHERE run_id = :runId AND provider = :provider
            ORDER BY ordinal
        """

        const val LOCK_PLANNED_RUN_FOR_ACTIVATION_SQL = """
            SELECT *
            FROM market_observation_runs
            WHERE run_id = :runId
              AND provider = :provider
              AND state = 'PLANNED'
            FOR UPDATE
        """

        const val LOCK_RUN_FOR_ACTIVATION_SQL = """
            SELECT *
            FROM market_observation_runs
            WHERE run_id = :runId
              AND provider = :provider
              AND state = 'PLANNED'
              AND :changedAt <= window_start
              AND window_end > :changedAt
              AND retention_until > :changedAt
            FOR UPDATE
        """

        const val ACTIVATE_RUN_SQL = """
            UPDATE market_observation_runs r
            SET state = 'RUNNING',
                started_at = :changedAt,
                activation_clock_sample_sequence = :expectedClockSampleSequence
            WHERE run_id = :runId
              AND provider = :provider
              AND state = 'PLANNED'
              AND latest_clock_sample_sequence = :expectedClockSampleSequence
              AND :expectedClockSampleSequence = $AS_OF_CLOCK_SAMPLE_SEQUENCE_SQL
              AND :changedAt <= window_start
              AND window_end > :changedAt
              AND retention_until > :changedAt
              AND $HEALTHY_AS_OF_CLOCK_SQL
        """

        const val LOCK_RUNNING_RUN_FOR_COMPLETION_DECISION_SQL = """
            SELECT *
            FROM market_observation_runs
            WHERE run_id = :runId
              AND provider = :provider
              AND state = 'RUNNING'
            FOR UPDATE
        """

        const val LOCK_RUN_FOR_COMPLETION_SQL = """
            SELECT TRUE AS locked
            FROM market_observation_runs r
            WHERE r.run_id = :runId
              AND r.provider = :provider
              AND r.state = :expected
              AND :expected = 'RUNNING'
              AND :updated = 'COMPLETED'
              AND r.retention_until > :changedAt
              AND :changedAt >= r.window_end
            FOR UPDATE OF r
        """

        const val COMPLETION_READY_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM market_observation_runs r
                WHERE r.run_id = :runId
                  AND r.provider = :provider
                  AND r.state = 'RUNNING'
                  AND r.retention_until > :changedAt
                  AND :changedAt >= r.window_end
                  AND $HEALTHY_AS_OF_CLOCK_SQL
                  AND NOT $INCOMPLETE_REST_EVIDENCE_SQL
            ) AS ready
        """

        const val TRANSITION_RUN_SQL = """
            UPDATE market_observation_runs r
            SET state = :updated,
                completed_at = CASE
                    WHEN :updated IN ('COMPLETED', 'INVALID') THEN :changedAt
                    ELSE completed_at
                END
            WHERE run_id = :runId
              AND provider = :provider
              AND state = :expected
              AND (:updated = 'INVALID' OR retention_until > :changedAt)
              AND (:updated <> 'COMPLETED' OR :changedAt >= window_end)
              AND (:updated <> 'COMPLETED' OR $HEALTHY_AS_OF_CLOCK_SQL)
              AND (:updated <> 'COMPLETED' OR NOT $INCOMPLETE_REST_EVIDENCE_SQL)
              AND (
                  (:expected = 'PLANNED' AND :updated = 'INVALID')
                  OR (:expected = 'RUNNING' AND :updated IN ('COMPLETED', 'INVALID'))
              )
        """

        const val CREATE_SEMANTICS_SQL = """
            INSERT INTO market_observation_semantics (
                run_id, provider, venue, session, candle_interval,
                timestamp_origin, timestamp_precision_micros, provider_zone_id,
                candle_time_convention, adjustment_mode, correction_policy,
                empty_minute_policy, volume_unit, provider_event_id_scope,
                document_evidence_id, document_evidence_sha256, confirmed_at
            )
            SELECT
                :runId, :provider, :venue, :session, :candleInterval,
                :timestampOrigin, :timestampPrecisionMicros, :providerZoneId,
                :candleTimeConvention, :adjustmentMode, :correctionPolicy,
                :emptyMinutePolicy, :volumeUnit, :providerEventIdScope,
                :documentEvidenceId, :documentEvidenceSha256, :confirmedAt
            FROM market_observation_runs r
            WHERE r.run_id = :runId
              AND r.provider = :provider
              AND r.state = 'PLANNED'
              AND r.retention_until > CURRENT_TIMESTAMP
            FOR SHARE OF r
        """

        const val FIND_SEMANTICS_SQL = """
            SELECT * FROM market_observation_semantics
            WHERE run_id = :runId AND provider = :provider
        """

        const val LOCK_RUN_FOR_CLOCK_APPEND_SQL = """
            SELECT r.latest_clock_sample_sequence,
                   c.sampled_at AS latest_clock_sampled_at
            FROM market_observation_runs r
            LEFT JOIN market_observation_clock_samples c
              ON c.run_id = r.run_id
             AND c.provider = r.provider
             AND c.sample_sequence = r.latest_clock_sample_sequence
            WHERE r.run_id = :runId
              AND r.provider = :provider
              AND r.state IN ('PLANNED', 'RUNNING')
              AND r.retention_until > CURRENT_TIMESTAMP
            FOR UPDATE OF r
        """

        const val INSERT_CLOCK_SAMPLE_SQL = """
            INSERT INTO market_observation_clock_samples (
                run_id, provider, sample_sequence, sampled_at,
                local_clock_offset_micros, uncertainty_micros, is_synchronized, clock_source
            )
            VALUES (
                :runId, :provider, :sampleSequence, :sampledAt,
                :localClockOffsetMicros, :uncertaintyMicros, :isSynchronized, :clockSource
            )
        """

        const val UPDATE_LATEST_CLOCK_SAMPLE_SQL = """
            UPDATE market_observation_runs r
            SET latest_clock_sample_sequence = :sampleSequence
            WHERE run_id = :runId AND provider = :provider
        """

        const val FIND_CLOCK_SAMPLE_SQL = """
            SELECT * FROM market_observation_clock_samples
            WHERE run_id = :runId
              AND provider = :provider
              AND sample_sequence = :sampleSequence
        """

        const val LATEST_CLOCK_SAMPLE_SQL = """
            SELECT c.*
            FROM market_observation_runs r
            JOIN market_observation_clock_samples c
              ON c.run_id = r.run_id
             AND c.provider = r.provider
             AND c.sample_sequence = r.latest_clock_sample_sequence
            WHERE r.run_id = :runId AND r.provider = :provider
        """

        const val LOCK_RUN_FOR_OBSERVATION_APPEND_SQL = """
            SELECT TRUE AS locked
            FROM market_observation_runs r
            WHERE r.run_id = :runId
              AND r.provider = :provider
              AND r.state = 'RUNNING'
              AND r.storage_right = 'ALLOWED'
              AND r.benchmark_right = 'ALLOWED'
              AND r.retention_until > CURRENT_TIMESTAMP
            FOR SHARE OF r
        """

        const val APPEND_REST_POLL_SQL = """
            INSERT INTO market_observation_rest_polls (
                run_id, provider, ticker, request_id, poll_run_id, page_ordinal,
                request_cursor, next_cursor, poll_terminal, observed_at,
                request_started_at, normalized_at, requested_from, requested_to,
                outcome, http_status, retry_after_millis, rate_limit_remaining,
                rate_limit_reset_at, round_robin_position, returned_candle_count,
                eligible_candle_count
            )
            SELECT
                :runId, :provider, :ticker, :requestId, :pollRunId, :pageOrdinal,
                :requestCursor, :nextCursor, :pollTerminal, :observedAt,
                :requestStartedAt, :normalizedAt, :requestedFrom, :requestedTo,
                :outcome, :httpStatus, :retryAfterMillis, :rateLimitRemaining,
                :rateLimitResetAt, :roundRobinPosition, :returnedCandleCount,
                :eligibleCandleCount
            FROM market_observation_runs r
            WHERE r.run_id = :runId AND r.provider = :provider
              AND r.state = 'RUNNING'
              AND r.storage_right = 'ALLOWED'
              AND r.benchmark_right = 'ALLOWED'
              AND r.retention_until > CURRENT_TIMESTAMP
              AND :requestStartedAt >= r.window_start
              AND :requestStartedAt < r.window_end
              AND $HEALTHY_AS_OF_CLOCK_SQL
              AND EXISTS (
                  SELECT 1 FROM market_observation_expected_tickers e
                  WHERE e.run_id = r.run_id
                    AND e.provider = r.provider
                    AND e.ticker = :ticker
              )
              AND (
                  :pageOrdinal = 0
                  OR EXISTS (
                      SELECT 1
                      FROM market_observation_rest_polls previous_page
                      WHERE previous_page.run_id = r.run_id
                        AND previous_page.provider = r.provider
                        AND previous_page.ticker = :ticker
                        AND previous_page.poll_run_id = :pollRunId
                        AND previous_page.page_ordinal = :pageOrdinal - 1
                        AND previous_page.outcome = 'SUCCESS'
                        AND previous_page.poll_terminal = FALSE
                        AND previous_page.next_cursor = :requestCursor
                        AND previous_page.requested_from = :requestedFrom
                        AND previous_page.requested_to = :requestedTo
                        AND previous_page.normalized_at <= :requestStartedAt
                  )
              )
            FOR SHARE OF r
        """

        const val APPEND_TICK_SQL = """
            INSERT INTO market_observation_ticks (
                run_id, provider, expected_ticker, reported_ticker,
                connection_epoch, local_receive_sequence,
                clock_sample_sequence, provider_event_id, provider_occurred_at,
                socket_received_at, normalized_at, price, quantity, outcome
            )
            SELECT
                :runId, :provider, :expectedTicker, :reportedTicker,
                :connectionEpoch, :localReceiveSequence,
                :clockSampleSequence, :providerEventId, :providerOccurredAt,
                :socketReceivedAt, :normalizedAt, :price, :quantity, :outcome
            FROM market_observation_runs r
            WHERE r.run_id = :runId AND r.provider = :provider
              AND r.state = 'RUNNING'
              AND r.storage_right = 'ALLOWED'
              AND r.benchmark_right = 'ALLOWED'
              AND r.retention_until > CURRENT_TIMESTAMP
              AND :socketReceivedAt >= r.window_start
              AND :socketReceivedAt < r.window_end
              AND $HEALTHY_AS_OF_CLOCK_SQL
              AND (
                  :outcome IN ('INVALID', 'MISROUTED')
                  OR :clockSampleSequence = $AS_OF_CLOCK_SAMPLE_SEQUENCE_SQL
              )
              AND EXISTS (
                  SELECT 1 FROM market_observation_expected_tickers e
                  WHERE e.run_id = r.run_id
                    AND e.provider = r.provider
                    AND e.ticker = :expectedTicker
              )
            FOR SHARE OF r
        """

        const val APPEND_CANDLE_SQL = """
            INSERT INTO market_observation_candles (
                run_id, provider, ticker, bucket_start, observation_sequence,
                provider_revision, observed_at, source, open, high, low, close,
                volume, trade_count, is_final, adjusted, rest_request_id
            )
            SELECT
                :runId, :provider, :ticker, :bucketStart, :observationSequence,
                :providerRevision, :observedAt, :source, :open, :high, :low, :close,
                :volume, :tradeCount, :isFinal, :adjusted, :restRequestId
            FROM market_observation_runs r
            WHERE r.run_id = :runId AND r.provider = :provider
              AND r.state = 'RUNNING'
              AND r.storage_right = 'ALLOWED'
              AND r.benchmark_right = 'ALLOWED'
              AND r.retention_until > CURRENT_TIMESTAMP
              AND :bucketStart >= r.window_start
              AND :bucketStart < r.window_end
              AND $HEALTHY_AS_OF_CLOCK_SQL
              AND EXISTS (
                  SELECT 1 FROM market_observation_expected_tickers e
                  WHERE e.run_id = r.run_id
                    AND e.provider = r.provider
                    AND e.ticker = :ticker
              )
              AND (
                  :source <> 'PROVIDER_REST'
                  OR EXISTS (
                      SELECT 1
                      FROM market_observation_rest_polls rp
                      WHERE rp.run_id = r.run_id
                        AND rp.provider = r.provider
                        AND rp.request_id = :restRequestId
                        AND rp.ticker = :ticker
                        AND rp.outcome = 'SUCCESS'
                        AND rp.normalized_at IS NOT NULL
                        AND :observedAt >= rp.observed_at
                        AND :bucketStart >= rp.requested_from
                        AND :bucketStart < rp.requested_to
                  )
              )
            FOR SHARE OF r
        """

        const val APPEND_FAULT_EVENT_SQL = """
            INSERT INTO market_observation_fault_events (
                run_id, provider, fault_id, event_sequence, event_type, observed_at,
                ticker, connection_epoch, gap_from, gap_to, http_status
            )
            SELECT
                :runId, :provider, :faultId, :eventSequence, :eventType, :observedAt,
                :ticker, :connectionEpoch, :gapFrom, :gapTo, :httpStatus
            FROM market_observation_runs r
            WHERE r.run_id = :runId AND r.provider = :provider
              AND r.state = 'RUNNING'
              AND r.storage_right = 'ALLOWED'
              AND r.benchmark_right = 'ALLOWED'
              AND r.retention_until > CURRENT_TIMESTAMP
              AND :observedAt >= r.window_start
              AND :observedAt < r.window_end
              AND $HEALTHY_AS_OF_CLOCK_SQL
              AND (
                  CAST(:ticker AS VARCHAR) IS NULL
                  OR EXISTS (
                      SELECT 1 FROM market_observation_expected_tickers e
                      WHERE e.run_id = r.run_id
                        AND e.provider = r.provider
                        AND e.ticker = :ticker
                  )
              )
            FOR SHARE OF r
        """

        const val EVIDENCE_SNAPSHOT_SQL = """
            SELECT
                r.state,
                (SELECT count(*) FROM market_observation_clock_samples c
                    WHERE c.run_id = r.run_id AND c.provider = r.provider) AS clock_sample_count,
                (SELECT count(*) FROM market_observation_rest_polls p
                    WHERE p.run_id = r.run_id AND p.provider = r.provider) AS rest_poll_count,
                (SELECT count(DISTINCT p.poll_run_id) FROM market_observation_rest_polls p
                    WHERE p.run_id = r.run_id AND p.provider = r.provider) AS rest_poll_run_count,
                (SELECT count(*) FROM market_observation_ticks t
                    WHERE t.run_id = r.run_id AND t.provider = r.provider) AS tick_count,
                (SELECT count(*) FROM market_observation_candles c
                    WHERE c.run_id = r.run_id AND c.provider = r.provider) AS candle_count,
                (SELECT count(*) FROM market_observation_fault_events f
                    WHERE f.run_id = r.run_id AND f.provider = r.provider) AS fault_event_count,
                (
                    SELECT min(observed_at)
                    FROM (
                        SELECT sampled_at AS observed_at FROM market_observation_clock_samples
                            WHERE run_id = r.run_id AND provider = r.provider
                        UNION ALL
                        SELECT observed_at FROM market_observation_rest_polls
                            WHERE run_id = r.run_id AND provider = r.provider
                        UNION ALL
                        SELECT socket_received_at FROM market_observation_ticks
                            WHERE run_id = r.run_id AND provider = r.provider
                        UNION ALL
                        SELECT observed_at FROM market_observation_candles
                            WHERE run_id = r.run_id AND provider = r.provider
                        UNION ALL
                        SELECT observed_at FROM market_observation_fault_events
                            WHERE run_id = r.run_id AND provider = r.provider
                    ) observed_times
                ) AS first_observed_at,
                (
                    SELECT max(observed_at)
                    FROM (
                        SELECT sampled_at AS observed_at FROM market_observation_clock_samples
                            WHERE run_id = r.run_id AND provider = r.provider
                        UNION ALL
                        SELECT observed_at FROM market_observation_rest_polls
                            WHERE run_id = r.run_id AND provider = r.provider
                        UNION ALL
                        SELECT socket_received_at FROM market_observation_ticks
                            WHERE run_id = r.run_id AND provider = r.provider
                        UNION ALL
                        SELECT observed_at FROM market_observation_candles
                            WHERE run_id = r.run_id AND provider = r.provider
                        UNION ALL
                        SELECT observed_at FROM market_observation_fault_events
                            WHERE run_id = r.run_id AND provider = r.provider
                    ) observed_times
                ) AS last_observed_at
            FROM market_observation_runs r
            WHERE r.run_id = :runId AND r.provider = :provider
        """

        const val FIND_EXPIRED_SCOPES_SQL = """
            SELECT run_id, provider
            FROM market_observation_runs
            WHERE retention_until <= :expiredAt
              AND retention_until <= CURRENT_TIMESTAMP
            ORDER BY retention_until, run_id, provider
            LIMIT :limit
        """

        const val LOCK_RUN_FOR_CLEANUP_SQL = """
            SELECT state, retention_until, CURRENT_TIMESTAMP AS database_now
            FROM market_observation_runs
            WHERE run_id = :runId AND provider = :provider
            FOR UPDATE
        """

        const val INVALIDATE_EXPIRED_RUN_SQL = """
            UPDATE market_observation_runs
            SET state = 'INVALID', completed_at = :requestedAt
            WHERE run_id = :runId
              AND provider = :provider
              AND state IN ('PLANNED', 'RUNNING')
              AND retention_until <= :requestedAt
              AND retention_until <= CURRENT_TIMESTAMP
              AND :requestedAt >= COALESCE(started_at, created_at)
        """

        const val DELETE_CANDLES_SQL = """
            DELETE FROM market_observation_candles
            WHERE run_id = :runId AND provider = :provider
        """

        const val DELETE_REST_POLLS_SQL = """
            DELETE FROM market_observation_rest_polls
            WHERE run_id = :runId AND provider = :provider
        """

        const val DELETE_TICKS_SQL = """
            DELETE FROM market_observation_ticks
            WHERE run_id = :runId AND provider = :provider
        """

        const val DELETE_FAULT_EVENTS_SQL = """
            DELETE FROM market_observation_fault_events
            WHERE run_id = :runId AND provider = :provider
        """

        const val DELETE_CLOCK_SAMPLES_SQL = """
            DELETE FROM market_observation_clock_samples
            WHERE run_id = :runId AND provider = :provider
        """

        const val DELETE_SEMANTICS_SQL = """
            DELETE FROM market_observation_semantics
            WHERE run_id = :runId AND provider = :provider
        """

        const val DELETE_EXPECTED_TICKERS_SQL = """
            DELETE FROM market_observation_expected_tickers
            WHERE run_id = :runId AND provider = :provider
        """

        const val DELETE_RUN_SQL = """
            DELETE FROM market_observation_runs
            WHERE run_id = :runId AND provider = :provider
        """

        const val INSERT_CLEANUP_AUDIT_SQL = """
            INSERT INTO market_observation_cleanup_audits (
                cleanup_id, run_id, provider, result, terminal_state, retention_until,
                requested_at, completed_at, semantics_deleted, expected_tickers_deleted,
                clock_samples_deleted, rest_polls_deleted, ticks_deleted,
                candles_deleted, fault_events_deleted
            ) VALUES (
                :cleanupId, :runId, :provider, :result, :terminalState, :retentionUntil,
                :requestedAt, :completedAt, :semanticsDeleted, :expectedTickersDeleted,
                :clockSamplesDeleted, :restPollsDeleted, :ticksDeleted,
                :candlesDeleted, :faultEventsDeleted
            )
        """

        val TERMINAL_STATES = setOf(ObservationRunState.COMPLETED, ObservationRunState.INVALID)

        val CANONICAL_ROW_QUERIES = listOf(
            CanonicalRowQuery(
                "run",
                """
                    SELECT role, origin, state, storage_right, benchmark_right, replay_right, ci_right,
                        internal_display_right, external_distribution_right, rights_evidence_id,
                        rights_evidence_sha256, benchmark_spec_id, benchmark_spec_sha256,
                        source_commit_sha, source_tree_dirty, window_start, window_end,
                        expected_ticker_count, ticker_set_sha256,
                        latest_clock_sample_sequence, activation_clock_sample_sequence,
                        retention_until, created_at, started_at, completed_at
                    FROM market_observation_runs
                    WHERE run_id = :runId AND provider = :provider
                """,
            ),
            CanonicalRowQuery(
                "expected-ticker",
                """
                    SELECT ticker, ordinal
                    FROM market_observation_expected_tickers
                    WHERE run_id = :runId AND provider = :provider
                    ORDER BY ticker
                """,
            ),
            CanonicalRowQuery(
                "semantics",
                """
                    SELECT venue, session, candle_interval, timestamp_origin,
                        timestamp_precision_micros, provider_zone_id, candle_time_convention,
                        adjustment_mode, correction_policy, empty_minute_policy, volume_unit,
                        provider_event_id_scope, document_evidence_id, document_evidence_sha256,
                        confirmed_at
                    FROM market_observation_semantics
                    WHERE run_id = :runId AND provider = :provider
                """,
            ),
            CanonicalRowQuery(
                "clock",
                """
                    SELECT sample_sequence, sampled_at, local_clock_offset_micros,
                        uncertainty_micros, is_synchronized, clock_source
                    FROM market_observation_clock_samples
                    WHERE run_id = :runId AND provider = :provider
                    ORDER BY sample_sequence
                """,
            ),
            CanonicalRowQuery(
                "rest",
                """
                    SELECT ticker, poll_run_id, page_ordinal, request_id,
                        request_cursor, next_cursor, poll_terminal,
                        observed_at, request_started_at, normalized_at,
                        requested_from, requested_to, outcome, http_status, retry_after_millis,
                        rate_limit_remaining, rate_limit_reset_at, round_robin_position,
                        returned_candle_count, eligible_candle_count
                    FROM market_observation_rest_polls
                    WHERE run_id = :runId AND provider = :provider
                    ORDER BY ticker, poll_run_id, page_ordinal
                """,
            ),
            CanonicalRowQuery(
                "tick",
                """
                    SELECT expected_ticker, reported_ticker, connection_epoch,
                        local_receive_sequence, clock_sample_sequence, provider_event_id,
                        provider_occurred_at, socket_received_at, normalized_at, price, quantity, outcome
                    FROM market_observation_ticks
                    WHERE run_id = :runId AND provider = :provider
                    ORDER BY connection_epoch, local_receive_sequence
                """,
            ),
            CanonicalRowQuery(
                "candle",
                """
                    SELECT ticker, bucket_start, observation_sequence, provider_revision, observed_at,
                        source, open, high, low, close, volume, trade_count, is_final, adjusted,
                        rest_request_id
                    FROM market_observation_candles
                    WHERE run_id = :runId AND provider = :provider
                    ORDER BY ticker, bucket_start, observation_sequence, source
                """,
            ),
            CanonicalRowQuery(
                "fault",
                """
                    SELECT fault_id, event_sequence, event_type, observed_at, ticker,
                        connection_epoch, gap_from, gap_to, http_status
                    FROM market_observation_fault_events
                    WHERE run_id = :runId AND provider = :provider
                    ORDER BY fault_id, event_sequence
                """,
            ),
        )
    }
}
