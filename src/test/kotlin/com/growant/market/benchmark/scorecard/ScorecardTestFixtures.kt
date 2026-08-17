package com.growant.market.benchmark.scorecard

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal object ScorecardTestFixtures {
    private const val FROZEN_VERSION = "1.0.0"

    fun seeminglyCompleteSchemaV1FrozenBundle(): LoadedScorecardBundle {
        val scorecards = ScorecardRole.entries.associateWith(::frozenScorecard)
        val manifest = ScorecardManifest(
            schemaVersion = MARKET_PROVIDER_SCORECARD_SCHEMA_VERSION,
            scorecardVersion = FROZEN_VERSION,
            state = ScorecardState.FROZEN,
            tiePolicy = TiePolicy(
                maxDifferencePointsInclusive = BigDecimal("3"),
                resolution = TieResolution.NO_FORCED_WINNER,
            ),
            entries = ScorecardRole.entries.map { role ->
                ScorecardFileEntry(
                    role = role,
                    fileName = fileName(role),
                    sha256 = sha256(role.name.toByteArray()),
                )
            },
        )
        return LoadedScorecardBundle(
            manifest = manifest,
            scorecards = scorecards,
            manifestSha256 = "f".repeat(64),
        )
    }

    fun writeSeeminglyCompleteSchemaV1FrozenBundle(directory: Path): Path {
        Files.createDirectories(directory)
        val bundle = seeminglyCompleteSchemaV1FrozenBundle()
        val entries = bundle.manifest.entries.map { entry ->
            val bytes = OBJECT_MAPPER.writeValueAsBytes(bundle.scorecards.getValue(entry.role))
            Files.write(directory.resolve(entry.fileName), bytes)
            entry.copy(sha256 = sha256(bytes))
        }
        Files.write(
            directory.resolve("manifest.json"),
            OBJECT_MAPPER.writeValueAsBytes(bundle.manifest.copy(entries = entries)),
        )
        return directory
    }

    fun replaceScorecard(
        bundle: LoadedScorecardBundle,
        role: ScorecardRole,
        transform: (RoleScorecard) -> RoleScorecard,
    ): LoadedScorecardBundle = bundle.copy(
        scorecards = bundle.scorecards.toMutableMap().also { scorecards ->
            scorecards[role] = transform(scorecards.getValue(role))
        },
    )

    private fun frozenScorecard(role: ScorecardRole): RoleScorecard {
        val areas = areas(role)
        val metrics = if (role == ScorecardRole.GROWANT_LOAD) {
            listOf(metric("growant.load-gate", required = true))
        } else {
            areas.mapIndexed { index, area ->
                metric("${area.id}.metric", required = index == 0)
            }
        }
        val linkedMetric = metrics.first()
        return RoleScorecard(
            schemaVersion = MARKET_PROVIDER_SCORECARD_SCHEMA_VERSION,
            scorecardVersion = FROZEN_VERSION,
            id = "growant.test.${role.name.lowercase().replace('_', '-')}",
            role = role,
            state = ScorecardState.FROZEN,
            scoringMode = if (role == ScorecardRole.GROWANT_LOAD) {
                ScoringMode.GATE_ONLY
            } else {
                ScoringMode.WEIGHTED_RANKING
            },
            scoredRunsAllowed = true,
            providerRankingAllowed = role != ScorecardRole.GROWANT_LOAD,
            documentedBaselineState = DocumentedBaselineState.EXECUTABLE,
            scoringMetricsState = ScoringMetricsState.FROZEN,
            areas = areas.mapIndexed { index, area ->
                area.copy(metricIds = listOf(metrics[index].id))
            },
            documentedHardGates = listOf(
                DocumentedHardGate(
                    id = "${role.name.lowercase().replace('_', '-')}.required-gate",
                    kind = HardGateKind.NUMERIC,
                    definitionState = DefinitionState.RESOLVED,
                    metricId = linkedMetric.id,
                    operator = GateOperator.LTE,
                    numericThreshold = BigDecimal("100"),
                    booleanThreshold = null,
                    thresholdVariable = null,
                    unit = linkedMetric.unit,
                    executable = true,
                ),
            ),
            metrics = metrics,
            unresolvedDecisionIds = emptyList(),
            provenance = listOf(
                ScorecardProvenance(
                    path = "docs/test-only-scorecard.md",
                    commit = "a".repeat(40),
                    contentSha256 = "b".repeat(64),
                ),
            ),
        )
    }

    private fun areas(role: ScorecardRole): List<WeightedArea> = when (role) {
        ScorecardRole.REALTIME_POC -> listOf(
            area("accuracy-recovery", "40"),
            area("latency-connectivity", "30"),
            area("backfill", "20"),
            area("implementation-complexity", "10"),
        )

        ScorecardRole.CANDLE_REFERENCE -> listOf(
            area("accuracy", "40"),
            area("publication-stability", "30"),
            area("history-pagination", "20"),
            area("implementation-complexity", "10"),
        )

        ScorecardRole.KOSPI_FEED -> listOf(
            area("coverage-sla", "30"),
            area("accuracy-recovery", "25"),
            area("latency", "15"),
            area("rights-total-cost", "20"),
            area("operational-complexity", "10"),
        )

        ScorecardRole.GROWANT_LOAD -> emptyList()
    }

    private fun area(id: String, weight: String) = WeightedArea(
        id = id,
        weightPoints = BigDecimal(weight),
        metricIds = emptyList(),
    )

    private fun metric(id: String, required: Boolean) = MetricSpec(
        id = id,
        unit = "points",
        direction = MetricDirection.LOWER_IS_BETTER,
        good = BigDecimal.ZERO,
        bad = BigDecimal("100"),
        required = required,
        notApplicablePolicy = if (required) {
            NotApplicablePolicy.DISALLOW
        } else {
            NotApplicablePolicy.SCORE_ZERO
        },
        aggregation = AggregationPlan(
            dimensions = listOf(AggregationDimension.RUN),
            steps = listOf(
                AggregationStep(
                    function = AggregationFunction.MEAN,
                    across = listOf(AggregationDimension.RUN),
                ),
            ),
        ),
    )

    private fun fileName(role: ScorecardRole): String =
        "benchmark-scorecard-${role.name.lowercase().replace('_', '-')}.json"

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val OBJECT_MAPPER: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()
}
