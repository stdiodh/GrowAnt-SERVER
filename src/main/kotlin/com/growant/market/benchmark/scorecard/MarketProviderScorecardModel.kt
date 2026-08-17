package com.growant.market.benchmark.scorecard

import java.math.BigDecimal

const val MARKET_PROVIDER_SCORECARD_SCHEMA_VERSION = 1

enum class ScorecardRole {
    REALTIME_POC,
    CANDLE_REFERENCE,
    KOSPI_FEED,
    GROWANT_LOAD,
}

enum class ScorecardState {
    DRAFT,
    FROZEN,
}

enum class TieResolution {
    NO_FORCED_WINNER,
}

enum class ScoringMode {
    WEIGHTED_RANKING,
    GATE_ONLY,
}

enum class DocumentedBaselineState {
    DOCUMENTED_NOT_EXECUTABLE,
    EXECUTABLE,
}

enum class ScoringMetricsState {
    UNRESOLVED,
    FROZEN,
}

enum class HardGateKind {
    NUMERIC,
    BOOLEAN,
    VARIABLE,
    CONCEPT,
}

enum class DefinitionState {
    UNRESOLVED,
    RESOLVED,
}

enum class GateOperator {
    LT,
    LTE,
    EQ,
    GTE,
    GT,
}

enum class MetricDirection {
    LOWER_IS_BETTER,
    HIGHER_IS_BETTER,
    BINARY_PASS,
}

enum class NotApplicablePolicy {
    DISALLOW,
    SCORE_ZERO,
    ROLE_NOT_APPLICABLE,
}

enum class AggregationDimension {
    RUN,
    TRADE_DATE,
    TICKER,
    BUCKET,
    REQUEST,
    SAMPLE,
    CONNECTION_EPOCH,
    FAULT_DURATION,
    FAULT_REPETITION,
}

enum class AggregationFunction {
    ALL,
    COUNT,
    SUM,
    MIN,
    MAX,
    MEAN,
    MEDIAN,
    NEAREST_RANK_P50,
    NEAREST_RANK_P95,
    NEAREST_RANK_P99,
    RATE,
}

data class ScorecardManifest(
    val schemaVersion: Int,
    val scorecardVersion: String,
    val state: ScorecardState,
    val tiePolicy: TiePolicy,
    val entries: List<ScorecardFileEntry>,
)

data class TiePolicy(
    val maxDifferencePointsInclusive: BigDecimal,
    val resolution: TieResolution,
)

data class ScorecardFileEntry(
    val role: ScorecardRole,
    val fileName: String,
    val sha256: String,
)

data class RoleScorecard(
    val schemaVersion: Int,
    val scorecardVersion: String,
    val id: String,
    val role: ScorecardRole,
    val state: ScorecardState,
    val scoringMode: ScoringMode,
    val scoredRunsAllowed: Boolean,
    val providerRankingAllowed: Boolean,
    val documentedBaselineState: DocumentedBaselineState,
    val scoringMetricsState: ScoringMetricsState,
    val areas: List<WeightedArea>,
    val documentedHardGates: List<DocumentedHardGate>,
    val metrics: List<MetricSpec>,
    val unresolvedDecisionIds: List<String>,
    val provenance: List<ScorecardProvenance>,
)

data class WeightedArea(
    val id: String,
    val weightPoints: BigDecimal,
    val metricIds: List<String>,
)

data class DocumentedHardGate(
    val id: String,
    val kind: HardGateKind,
    val definitionState: DefinitionState,
    val metricId: String?,
    val operator: GateOperator?,
    val numericThreshold: BigDecimal?,
    val booleanThreshold: Boolean?,
    val thresholdVariable: String?,
    val unit: String?,
    val executable: Boolean,
)

data class MetricSpec(
    val id: String,
    val unit: String,
    val direction: MetricDirection,
    val good: BigDecimal,
    val bad: BigDecimal,
    val required: Boolean,
    val notApplicablePolicy: NotApplicablePolicy,
    val aggregation: AggregationPlan,
)

data class AggregationPlan(
    val dimensions: List<AggregationDimension>,
    val steps: List<AggregationStep>,
)

data class AggregationStep(
    val function: AggregationFunction,
    val across: List<AggregationDimension>,
)

data class ScorecardProvenance(
    val path: String,
    val commit: String,
    val contentSha256: String,
)

data class LoadedScorecardBundle(
    val manifest: ScorecardManifest,
    val scorecards: Map<ScorecardRole, RoleScorecard>,
    val manifestSha256: String,
)

class MarketProviderScorecardValidationException(
    message: String,
    val violations: List<String> = listOf(message),
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
