package com.growant.market.observation.export

import com.growant.market.observation.MarketDataSemantics
import com.growant.market.observation.ObservationRights
import com.growant.market.observation.ObservationTickerSetChecksum
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.UUID

class ObservationEvidenceManifestWriter {
    fun write(
        outputFile: Path,
        manifest: ObservationEvidenceManifest,
    ): Path {
        Files.write(
            outputFile,
            manifest.toCanonicalJson().toByteArray(StandardCharsets.UTF_8),
            CREATE_NEW,
            WRITE,
        )
        return outputFile
    }
}

data class ObservationEvidenceManifest(
    val observationSchemaVersion: Int,
    val runId: UUID,
    val provider: String,
    val role: String,
    val origin: String,
    val state: String,
    val benchmarkSpecId: String,
    val benchmarkSpecChecksumSha256: String,
    val sourceCommitSha: String,
    val sourceTreeDirty: Boolean,
    val windowStart: Instant,
    val windowEnd: Instant,
    val expectedTickerCount: Int,
    val tickerSetChecksumSha256: String,
    val tickersInOrdinalOrder: List<String>,
    val startedAt: Instant?,
    val completedAt: Instant,
    val exportedAt: Instant,
    val retentionUntil: Instant,
    val activationClockSampleSequence: Long?,
    val clockSampledAt: Instant?,
    val localClockOffsetMicros: Long?,
    val clockUncertaintyMicros: Long?,
    val clockSynchronized: Boolean?,
    val clockSource: String?,
    val semantics: MarketDataSemantics?,
    val rights: ObservationRights,
    val rowChecksumSha256: String,
    val recordCounts: Map<String, Long>,
) {
    init {
        require(observationSchemaVersion > 0) { "observationSchemaVersion must be positive" }
        require(state == "COMPLETED" || state == "INVALID") {
            "Only completed or invalid observation runs can be exported"
        }
        if (state == "COMPLETED") {
            require(
                startedAt != null &&
                    semantics != null &&
                    activationClockSampleSequence != null &&
                    clockSampledAt != null &&
                    tickersInOrdinalOrder.size == expectedTickerCount &&
                    ObservationTickerSetChecksum.sha256(tickersInOrdinalOrder) == tickerSetChecksumSha256
            ) {
                "Completed observation runs require complete evidence metadata"
            }
        }
        require(startedAt == null || !completedAt.isBefore(startedAt)) {
            "completedAt must not be before startedAt"
        }
        require(!exportedAt.isBefore(completedAt)) { "exportedAt must not be before completedAt" }
        require(retentionUntil.isAfter(exportedAt)) { "retentionUntil must be after exportedAt" }
        val clockFields = listOf(
            activationClockSampleSequence,
            clockSampledAt,
            localClockOffsetMicros,
            clockUncertaintyMicros,
            clockSynchronized,
            clockSource,
        )
        require(clockFields.all { it == null } || clockFields.none { it == null }) {
            "clock evidence fields must be present together"
        }
        require(activationClockSampleSequence == null || activationClockSampleSequence >= 0) {
            "activationClockSampleSequence must not be negative"
        }
        require(clockUncertaintyMicros == null || clockUncertaintyMicros >= 0) {
            "clockUncertaintyMicros must not be negative"
        }
        require(provider.isSafeProvider()) {
            "provider must be a non-blank identifier of at most 40 characters"
        }
        require(role.isSafeIdentifier() && origin.isSafeIdentifier()) {
            "role and origin must be stable identifiers"
        }
        require(benchmarkSpecId.isSafeEvidenceIdentifier()) {
            "benchmarkSpecId must be a safe identifier"
        }
        require(benchmarkSpecChecksumSha256.isSha256()) {
            "benchmarkSpecChecksumSha256 must be a lowercase SHA-256 value"
        }
        require(sourceCommitSha.matches(COMMIT_SHA)) {
            "sourceCommitSha must be a lowercase full commit SHA"
        }
        require(windowStart < windowEnd) { "windowStart must be before windowEnd" }
        require(retentionUntil > windowEnd) { "retentionUntil must be after windowEnd" }
        require(expectedTickerCount in 1..EXPECTED_TICKER_COUNT_MAX) {
            "expectedTickerCount must be between 1 and $EXPECTED_TICKER_COUNT_MAX"
        }
        require(tickerSetChecksumSha256.isSha256()) {
            "tickerSetChecksumSha256 must be a lowercase SHA-256 value"
        }
        require(tickersInOrdinalOrder.size <= expectedTickerCount) {
            "captured expected tickers must not exceed the declared count"
        }
        require(tickersInOrdinalOrder.all { it.matches(TICKER) }) {
            "expected tickers must contain six digits"
        }
        require(tickersInOrdinalOrder.distinct().size == tickersInOrdinalOrder.size) {
            "expected tickers must not contain duplicates"
        }
        require(clockSource == null || clockSource.isSafeIdentifier()) {
            "clockSource must be a stable identifier"
        }
        require(semantics == null || semantics.scope.runId == runId && semantics.scope.provider == provider) {
            "semantics scope must match the manifest identity"
        }
        require(rowChecksumSha256.isSha256()) {
            "rowChecksumSha256 must be a lowercase SHA-256 value"
        }
        require(recordCounts.all { (name, count) -> name.isSafeCountName() && count >= 0 }) {
            "recordCounts must contain safe names and non-negative values"
        }
    }

    fun toCanonicalJson(): String = buildString {
        appendLine("{")
        appendJsonString("manifestFormatVersion", MANIFEST_FORMAT_VERSION, trailingComma = true)
        appendJsonNumber("observationSchemaVersion", observationSchemaVersion.toLong(), trailingComma = true)
        appendJsonString("runId", runId.toString(), trailingComma = true)
        appendJsonString("provider", provider, trailingComma = true)
        appendJsonString("role", role, trailingComma = true)
        appendJsonString("origin", origin, trailingComma = true)
        appendJsonString("state", state, trailingComma = true)
        appendJsonString("benchmarkSpecId", benchmarkSpecId, trailingComma = true)
        appendJsonString("benchmarkSpecChecksumSha256", benchmarkSpecChecksumSha256, trailingComma = true)
        appendJsonString("sourceCommitSha", sourceCommitSha, trailingComma = true)
        appendJsonBoolean("sourceTreeDirty", sourceTreeDirty, trailingComma = true)
        appendJsonString("windowStart", windowStart.toString(), trailingComma = true)
        appendJsonString("windowEnd", windowEnd.toString(), trailingComma = true)
        appendJsonNumber("expectedTickerCount", expectedTickerCount.toLong(), trailingComma = true)
        appendJsonString("tickerSetChecksumSha256", tickerSetChecksumSha256, trailingComma = true)
        appendJsonStringArray("tickersInOrdinalOrder", tickersInOrdinalOrder, trailingComma = true)
        appendJsonNullableString("startedAt", startedAt?.toString(), trailingComma = true)
        appendJsonString("completedAt", completedAt.toString(), trailingComma = true)
        appendJsonString("exportedAt", exportedAt.toString(), trailingComma = true)
        appendJsonString("retentionUntil", retentionUntil.toString(), trailingComma = true)
        if (clockSampledAt == null) {
            appendLine("  \"clock\": null,")
        } else {
            appendLine("  \"clock\": {")
            appendJsonNumber("sampleSequence", activationClockSampleSequence!!, indent = 4, trailingComma = true)
            appendJsonString("sampledAt", clockSampledAt.toString(), indent = 4, trailingComma = true)
            appendJsonNumber("localOffsetMicros", localClockOffsetMicros!!, indent = 4, trailingComma = true)
            appendJsonNumber("uncertaintyMicros", clockUncertaintyMicros!!, indent = 4, trailingComma = true)
            appendJsonBoolean("synchronized", clockSynchronized!!, indent = 4, trailingComma = true)
            appendJsonString("source", clockSource!!, indent = 4)
            appendLine("  },")
        }
        if (semantics == null) {
            appendLine("  \"semantics\": null,")
        } else {
            appendLine("  \"semantics\": {")
            appendJsonString("venue", semantics.venue.name, indent = 4, trailingComma = true)
            appendJsonString("session", semantics.session.name, indent = 4, trailingComma = true)
            appendJsonString("interval", semantics.interval.name, indent = 4, trailingComma = true)
            appendJsonString("timestampOrigin", semantics.timestampOrigin.name, indent = 4, trailingComma = true)
            appendJsonNullableNumber(
                "timestampPrecisionMicros",
                semantics.timestampPrecisionMicros,
                indent = 4,
                trailingComma = true,
            )
            appendJsonNullableString(
                "providerZoneId",
                semantics.providerZoneId?.id,
                indent = 4,
                trailingComma = true,
            )
            appendJsonString(
                "candleTimeConvention",
                semantics.candleTimeConvention.name,
                indent = 4,
                trailingComma = true,
            )
            appendJsonString("adjustmentMode", semantics.adjustmentMode.name, indent = 4, trailingComma = true)
            appendJsonString("correctionPolicy", semantics.correctionPolicy.name, indent = 4, trailingComma = true)
            appendJsonString("emptyMinutePolicy", semantics.emptyMinutePolicy.name, indent = 4, trailingComma = true)
            appendJsonString("volumeUnit", semantics.volumeUnit.name, indent = 4, trailingComma = true)
            appendJsonString(
                "providerEventIdScope",
                semantics.providerEventIdScope.name,
                indent = 4,
                trailingComma = true,
            )
            appendJsonNullableString(
                "confirmedAt",
                semantics.confirmedAt?.toString(),
                indent = 4,
            )
            appendLine("  },")
        }
        appendLine("  \"rights\": {")
        appendJsonString("storage", rights.storage.name, indent = 4, trailingComma = true)
        appendJsonString("benchmark", rights.benchmark.name, indent = 4, trailingComma = true)
        appendJsonString("replay", rights.replay.name, indent = 4, trailingComma = true)
        appendJsonString("ci", rights.ci.name, indent = 4, trailingComma = true)
        appendJsonString("internalDisplay", rights.internalDisplay.name, indent = 4, trailingComma = true)
        appendJsonString(
            "externalDistribution",
            rights.externalDistribution.name,
            indent = 4,
            trailingComma = false,
        )
        appendLine("  },")
        appendLine("  \"checksums\": {")
        appendJsonNullableString(
            "semanticsSha256",
            semantics?.documentEvidenceChecksumSha256,
            indent = 4,
            trailingComma = true,
        )
        appendJsonString("rightsSha256", rights.evidenceChecksumSha256, indent = 4, trailingComma = true)
        appendJsonString("rowsSha256", rowChecksumSha256, indent = 4)
        appendLine("  },")
        appendLine("  \"recordCounts\": {")
        recordCounts.toSortedMap().entries.forEachIndexed { index, (name, count) ->
            appendJsonNumber(
                name = name,
                value = count,
                indent = 4,
                trailingComma = index < recordCounts.size - 1,
            )
        }
        appendLine("  }")
        appendLine("}")
    }

    private companion object {
        const val MANIFEST_FORMAT_VERSION = "1"
        const val EXPECTED_TICKER_COUNT_MAX = 10_000
        val SHA_256 = Regex("[0-9a-f]{64}")
        val COMMIT_SHA = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
        val SAFE_PROVIDER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")
        val SAFE_IDENTIFIER = Regex("[A-Z][A-Z0-9_]{0,63}")
        val SAFE_EVIDENCE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
        val SAFE_COUNT_NAME = Regex("[a-z][a-z0-9_.-]{0,63}")
        val TICKER = Regex("[0-9]{6}")
    }

    private fun String.isSha256() = matches(SHA_256)

    private fun String.isSafeProvider() = matches(SAFE_PROVIDER)

    private fun String.isSafeIdentifier() = matches(SAFE_IDENTIFIER)

    private fun String.isSafeEvidenceIdentifier() = matches(SAFE_EVIDENCE_IDENTIFIER)

    private fun String.isSafeCountName() = matches(SAFE_COUNT_NAME)
}

private fun StringBuilder.appendJsonBoolean(
    name: String,
    value: Boolean,
    indent: Int = 2,
    trailingComma: Boolean = false,
) {
    append(" ".repeat(indent))
    append(name.toJsonString())
    append(": ")
    append(value)
    if (trailingComma) append(',')
    appendLine()
}

private fun StringBuilder.appendJsonStringArray(
    name: String,
    values: List<String>,
    indent: Int = 2,
    trailingComma: Boolean = false,
) {
    append(" ".repeat(indent))
    append(name.toJsonString())
    appendLine(": [")
    values.forEachIndexed { index, value ->
        append(" ".repeat(indent + 2))
        append(value.toJsonString())
        if (index < values.lastIndex) append(',')
        appendLine()
    }
    append(" ".repeat(indent))
    append(']')
    if (trailingComma) append(',')
    appendLine()
}

private fun StringBuilder.appendJsonString(
    name: String,
    value: String,
    indent: Int = 2,
    trailingComma: Boolean = false,
) {
    append(" ".repeat(indent))
    append(name.toJsonString())
    append(": ")
    append(value.toJsonString())
    if (trailingComma) append(',')
    appendLine()
}

private fun StringBuilder.appendJsonNullableString(
    name: String,
    value: String?,
    indent: Int = 2,
    trailingComma: Boolean = false,
) {
    append(" ".repeat(indent))
    append(name.toJsonString())
    append(": ")
    append(value?.toJsonString() ?: "null")
    if (trailingComma) append(',')
    appendLine()
}

private fun StringBuilder.appendJsonNumber(
    name: String,
    value: Long,
    indent: Int = 2,
    trailingComma: Boolean = false,
) {
    append(" ".repeat(indent))
    append(name.toJsonString())
    append(": ")
    append(value)
    if (trailingComma) append(',')
    appendLine()
}

private fun StringBuilder.appendJsonNullableNumber(
    name: String,
    value: Long?,
    indent: Int = 2,
    trailingComma: Boolean = false,
) {
    append(" ".repeat(indent))
    append(name.toJsonString())
    append(": ")
    if (value == null) append("null") else append(value)
    if (trailingComma) append(',')
    appendLine()
}

private fun String.toJsonString(): String = buildString {
    append('"')
    this@toJsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
