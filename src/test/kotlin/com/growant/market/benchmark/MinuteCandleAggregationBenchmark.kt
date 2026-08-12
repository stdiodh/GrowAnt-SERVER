package com.growant.market.benchmark

import com.growant.market.candle.MinuteCandleAggregator
import com.growant.market.candle.TradeTick
import java.nio.file.Files
import java.nio.file.Path
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil

object MinuteCandleAggregationBenchmark {
    private val tickers = listOf("005930", "000660", "035420", "035720", "005380")
    private val basePrices = listOf(73_000, 190_000, 210_000, 48_000, 250_000)
    private val marketOpen = Instant.parse("2026-08-03T00:00:00Z")
    private const val marketMinutes = 390

    @JvmStatic
    fun main(args: Array<String>) {
        val configuration = Configuration.parse(args)
        val ticks = generateReplayTicks(configuration.ticksPerMinute)
        val expectedCandleCount = tickers.size * marketMinutes
        val expectedVolume = ticks.sumOf(TradeTick::quantity)
        val finalizeBefore = marketOpen.plusSeconds(marketMinutes * 60L)

        repeat(configuration.warmupRounds) {
            runRound(ticks, finalizeBefore, expectedCandleCount, expectedVolume)
        }

        val measurements = (1..configuration.measurementRounds).map { round ->
            val result = runRound(ticks, finalizeBefore, expectedCandleCount, expectedVolume)
            Measurement(
                round = round,
                durationNanos = result.durationNanos,
                ticksPerSecond = ticks.size / (result.durationNanos / 1_000_000_000.0),
                candleCount = result.candleCount,
                checksum = result.checksum,
            )
        }

        val report = renderReport(configuration, ticks.size, measurements)
        configuration.output.parent?.let(Files::createDirectories)
        Files.writeString(configuration.output, report)
        print(report)
    }

    private fun generateReplayTicks(ticksPerMinute: Int): List<TradeTick> {
        val ticks = ArrayList<TradeTick>(tickers.size * marketMinutes * ticksPerMinute)
        var sequence = 1L

        repeat(marketMinutes) { minute ->
            repeat(ticksPerMinute) { tickIndex ->
                tickers.forEachIndexed { tickerIndex, ticker ->
                    val offsetMillis = tickIndex * 60_000L / ticksPerMinute
                    val occurredAt = marketOpen
                        .plusSeconds(minute * 60L)
                        .plusMillis(offsetMillis)
                    val priceMovement = ((minute * 7 + tickIndex * 3 + tickerIndex) % 101) - 50
                    ticks += TradeTick(
                        ticker = ticker,
                        price = basePrices[tickerIndex] + priceMovement * 10,
                        quantity = (tickIndex % 10 + 1).toLong(),
                        occurredAt = occurredAt,
                        sequence = sequence++,
                    )
                }
            }
        }

        return ticks
    }

    private fun runRound(
        ticks: List<TradeTick>,
        finalizeBefore: Instant,
        expectedCandleCount: Int,
        expectedVolume: Long,
    ): RoundResult {
        val aggregator = MinuteCandleAggregator(source = "replay")
        val startedAt = System.nanoTime()
        ticks.forEach(aggregator::accept)
        val candles = aggregator.drainFinalized(finalizeBefore)
        val durationNanos = System.nanoTime() - startedAt

        check(candles.size == expectedCandleCount) {
            "expected $expectedCandleCount candles but got ${candles.size}"
        }
        check(candles.sumOf { it.volume } == expectedVolume) {
            "aggregated volume did not match replay volume"
        }
        check(aggregator.snapshots().isEmpty()) {
            "all replay buckets should be finalized"
        }

        return RoundResult(
            durationNanos = durationNanos,
            candleCount = candles.size,
            checksum = candles.sumOf { candle ->
                candle.open.toLong() + candle.high + candle.low + candle.close + candle.volume + candle.tradeCount
            },
        )
    }

    private fun renderReport(
        configuration: Configuration,
        tickCount: Int,
        measurements: List<Measurement>,
    ): String {
        val durations = measurements.map(Measurement::durationNanos)
        val throughputs = measurements.map(Measurement::ticksPerSecond)
        val checksum = measurements.map(Measurement::checksum).distinct().single()

        return buildString {
            appendLine("# Minute Candle Aggregation Benchmark")
            appendLine()
            configuration.runId?.let { appendLine("- Run ID: $it") }
            appendLine(
                "- Dataset: ${tickers.size} tickers × $marketMinutes minutes × " +
                    "${configuration.ticksPerMinute} ticks/minute/ticker",
            )
            appendLine("- Replay ticks: ${integerFormat.format(tickCount)}")
            appendLine("- Expected candles per round: ${integerFormat.format(tickers.size * marketMinutes)}")
            appendLine("- Execution: single-threaded in-memory replay including finalization")
            appendLine("- Warm-up rounds: ${configuration.warmupRounds}")
            appendLine("- Measurement rounds: ${configuration.measurementRounds}")
            appendLine("- Runtime: ${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}")
            appendLine("- Available processors: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("- Result checksum: $checksum")
            appendLine()
            appendLine("| Round | Duration (ms) | Throughput (ticks/s) | Candles |")
            appendLine("| ---: | ---: | ---: | ---: |")
            measurements.forEach { measurement ->
                appendLine(
                    "| ${measurement.round} | ${decimalFormat.format(measurement.durationNanos / 1_000_000.0)} | " +
                        "${integerFormat.format(measurement.ticksPerSecond)} | ${integerFormat.format(measurement.candleCount)} |",
                )
            }
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("- Duration median: ${decimalFormat.format(percentile(durations, 0.50) / 1_000_000.0)} ms")
            appendLine("- Duration p95: ${decimalFormat.format(percentile(durations, 0.95) / 1_000_000.0)} ms")
            appendLine("- Throughput median: ${integerFormat.format(percentile(throughputs, 0.50))} ticks/s")
            appendLine("- Throughput minimum: ${integerFormat.format(throughputs.min())} ticks/s")
            appendLine()
            appendLine("> Timing values are observations, not pass/fail assertions. Run on the target server for capacity decisions.")
        }
    }

    private fun <T : Comparable<T>> percentile(values: List<T>, percentile: Double): T {
        val sorted = values.sorted()
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private data class RoundResult(
        val durationNanos: Long,
        val candleCount: Int,
        val checksum: Long,
    )

    private data class Measurement(
        val round: Int,
        val durationNanos: Long,
        val ticksPerSecond: Double,
        val candleCount: Int,
        val checksum: Long,
    )

    private data class Configuration(
        val ticksPerMinute: Int,
        val warmupRounds: Int,
        val measurementRounds: Int,
        val output: Path,
        val runId: String?,
    ) {
        companion object {
            fun parse(args: Array<String>): Configuration {
                val options = args.associate { argument ->
                    require(argument.startsWith("--") && argument.contains("=")) {
                        "arguments must use --name=value format"
                    }
                    argument.removePrefix("--").split("=", limit = 2).let { it[0] to it[1] }
                }

                return Configuration(
                    ticksPerMinute = options.positiveInt("ticks-per-minute", 1_000),
                    warmupRounds = options.positiveInt("warmup-rounds", 3),
                    measurementRounds = options.positiveInt("measurement-rounds", 7),
                    output = Path.of(
                        options["output"]
                            ?: "build/reports/market-data/minute-candle-aggregation-benchmark.md",
                    ),
                    runId = options["run-id"],
                )
            }

            private fun Map<String, String>.positiveInt(name: String, defaultValue: Int): Int {
                val rawValue = get(name) ?: return defaultValue
                val value = rawValue.toIntOrNull()
                require(value != null && value > 0) { "$name must be a positive integer" }
                return value
            }
        }
    }

    private val decimalFormat = DecimalFormat("#,##0.000", DecimalFormatSymbols.getInstance(Locale.ROOT))
    private val integerFormat = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT))
}
