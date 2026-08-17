import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";
import { Counter, Rate, Trend } from "k6/metrics";

function requiredEnvironmentVariable(name) {
    const value = __ENV[name];
    if (!value) {
        throw new Error(`${name} environment variable is required`);
    }
    return value;
}

function positiveIntegerEnvironmentVariable(name, defaultValue) {
    const rawValue = __ENV[name] || defaultValue;
    const value = Number(rawValue);
    if (!Number.isInteger(value) || value <= 0) {
        throw new Error(`${name} must be a positive integer`);
    }
    return value;
}

function nonNegativeIntegerEnvironmentVariable(name, defaultValue) {
    const rawValue = __ENV[name] || defaultValue;
    const value = Number(rawValue);
    if (!Number.isInteger(value) || value < 0) {
        throw new Error(`${name} must be a non-negative integer`);
    }
    return value;
}

function rateEnvironmentVariable(name, defaultValue) {
    const rawValue = __ENV[name] || defaultValue;
    const value = Number(rawValue);
    if (!Number.isFinite(value) || value <= 0 || value >= 1) {
        throw new Error(`${name} must be greater than 0 and less than 1`);
    }
    return value;
}

function enumEnvironmentVariable(name, defaultValue, allowedValues) {
    const value = __ENV[name] || defaultValue;
    if (!allowedValues.includes(value)) {
        throw new Error(`${name} must be one of: ${allowedValues.join(", ")}`);
    }
    return value;
}

function tickersEnvironmentVariable() {
    const tickers = requiredEnvironmentVariable("TICKERS")
        .split(",")
        .map((ticker) => ticker.trim())
        .filter((ticker) => ticker.length > 0);
    const uniqueTickers = new Set(tickers);
    const expectedCount = positiveIntegerEnvironmentVariable("EXPECTED_TICKER_COUNT", String(tickers.length));

    if (tickers.length === 0 || tickers.some((ticker) => !/^\d{6}$/.test(ticker))) {
        throw new Error("TICKERS must be a comma-separated list of six-digit ticker codes");
    }
    if (uniqueTickers.size !== tickers.length) {
        throw new Error("TICKERS must not contain duplicates");
    }
    if (tickers.length !== expectedCount) {
        throw new Error(`TICKERS count ${tickers.length} does not match EXPECTED_TICKER_COUNT ${expectedCount}`);
    }

    return tickers;
}

function rangeEnvironmentVariables(prefix, defaultMinimumCandles) {
    const from = requiredEnvironmentVariable(`${prefix}_FROM`);
    const to = requiredEnvironmentVariable(`${prefix}_TO`);
    const fromEpochMillis = Date.parse(from);
    const toEpochMillis = Date.parse(to);

    if (!Number.isFinite(fromEpochMillis) || !Number.isFinite(toEpochMillis) || fromEpochMillis >= toEpochMillis) {
        throw new Error(`${prefix}_FROM and ${prefix}_TO must be an increasing ISO-8601 range`);
    }

    return {
        name: prefix.toLowerCase(),
        from,
        to,
        minimumCandles: positiveIntegerEnvironmentVariable(`${prefix}_MIN_CANDLES`, defaultMinimumCandles),
    };
}

function stagesEnvironmentVariable() {
    const rawValue =
        __ENV.STAGES_JSON ||
        '[{"duration":"2m","target":25},{"duration":"3m","target":50},{"duration":"3m","target":100},{"duration":"2m","target":0}]';
    let stages;

    try {
        stages = JSON.parse(rawValue);
    } catch (_) {
        throw new Error("STAGES_JSON must be valid JSON");
    }

    if (
        !Array.isArray(stages) ||
        stages.length === 0 ||
        stages.some(
            (stage) =>
                typeof stage !== "object" ||
                typeof stage.duration !== "string" ||
                !Number.isInteger(stage.target) ||
                stage.target < 0,
        )
    ) {
        throw new Error("STAGES_JSON must contain duration strings and non-negative integer targets");
    }

    return stages;
}

const baseUrl = requiredEnvironmentVariable("BASE_URL").replace(/\/+$/, "");
const tickers = tickersEnvironmentVariable();
const recentRange = rangeEnvironmentVariables("RECENT", "48");
const dayRange = rangeEnvironmentVariables("DAY", "390");
const weekRange = rangeEnvironmentVariables("WEEK", "1950");
const token = __ENV.TOKEN;
const p95LimitMs = positiveIntegerEnvironmentVariable("P95_LIMIT_MS", "200");
const errorRateLimit = rateEnvironmentVariable("ERROR_RATE_LIMIT", "0.01");
const checkRateLimit = rateEnvironmentVariable("CHECK_RATE_LIMIT", "0.99");
const preAllocatedVUs = positiveIntegerEnvironmentVariable("PRE_ALLOCATED_VUS", "100");
const maxVUs = positiveIntegerEnvironmentVariable("MAX_VUS", "500");
const startRate = nonNegativeIntegerEnvironmentVariable("START_RATE", "10");
const stages = stagesEnvironmentVariable();
const minimumTotalRequests = positiveIntegerEnvironmentVariable("MIN_TOTAL_REQUESTS", "100");
const minimumRangeRequests = positiveIntegerEnvironmentVariable("MIN_RANGE_REQUESTS", "1");
const tickerDistribution = enumEnvironmentVariable("TICKER_DISTRIBUTION", "uniform", ["uniform", "hot-80-20"]);

if (maxVUs < preAllocatedVUs) {
    throw new Error("MAX_VUS must be greater than or equal to PRE_ALLOCATED_VUS");
}
if (startRate === 0 && stages.every((stage) => stage.target === 0)) {
    throw new Error("START_RATE and at least one stage target must produce requests");
}

const requestDuration = new Trend("candles_request_duration", true);
const recentRequestDuration = new Trend("candles_recent_duration", true);
const dayRequestDuration = new Trend("candles_day_duration", true);
const weekRequestDuration = new Trend("candles_week_duration", true);
const successfulResponses = new Rate("candles_success");
const requestCount = new Counter("candles_requests");

export const options = {
    scenarios: {
        mixed_candle_reads: {
            executor: "ramping-arrival-rate",
            startRate,
            timeUnit: "1s",
            preAllocatedVUs,
            maxVUs,
            stages,
            gracefulStop: "30s",
        },
    },
    thresholds: {
        http_req_failed: [`rate<${errorRateLimit}`],
        checks: [`rate>${checkRateLimit}`],
        candles_success: [`rate>${checkRateLimit}`],
        candles_request_duration: [`p(95)<${p95LimitMs}`],
        "candles_request_duration{range:recent}": [`p(95)<${p95LimitMs}`],
        "candles_request_duration{range:day}": [`p(95)<${p95LimitMs}`],
        "candles_request_duration{range:week}": [`p(95)<${p95LimitMs}`],
        candles_requests: [`count>=${minimumTotalRequests}`],
        "candles_requests{range:recent}": [`count>=${minimumRangeRequests}`],
        "candles_requests{range:day}": [`count>=${minimumRangeRequests}`],
        "candles_requests{range:week}": [`count>=${minimumRangeRequests}`],
        dropped_iterations: ["count==0"],
    },
    summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const headers = {
    Accept: "application/json",
    "Accept-Encoding": "gzip",
};

if (token) {
    headers.Authorization = `Bearer ${token}`;
}

const ranges = [recentRange, dayRange, weekRange];
const rangeDurationMetrics = {
    recent: recentRequestDuration,
    day: dayRequestDuration,
    week: weekRequestDuration,
};

export default function () {
    const iteration = exec.scenario.iterationInTest;
    const tickerIndex = selectTickerIndex(iteration);
    const ticker = tickers[tickerIndex];
    const range = selectRange(iteration, tickerIndex);
    const tags = { ticker, range: range.name };
    const url =
        `${baseUrl}/api/market/${encodeURIComponent(ticker)}/candles` +
        `?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`;
    const response = http.get(url, {
        headers,
        tags: { name: "/api/market/:ticker/candles", ...tags },
        redirects: 0,
    });
    const successful = isSuccessfulCandlesResponse(response, range.minimumCandles);

    check(response, {
        "candles response matches the basic contract": () => successful,
    });
    successfulResponses.add(successful, tags);
    requestCount.add(1, tags);
    requestDuration.add(response.timings.duration, tags);
    rangeDurationMetrics[range.name].add(response.timings.duration, { ticker });
}

function selectTickerIndex(iteration) {
    if (tickerDistribution === "uniform" || tickers.length === 1) {
        return iteration % tickers.length;
    }

    const popularTickerCount = Math.max(1, Math.ceil(tickers.length * 0.2));
    const otherTickerCount = tickers.length - popularTickerCount;
    if (otherTickerCount === 0) {
        return iteration % tickers.length;
    }

    const block = Math.floor(iteration / 100);
    const blockSlot = iteration % 100;
    const distributionGroup = Math.floor(blockSlot / 20);
    const groupSlot = blockSlot % 20;
    if (distributionGroup < 4) {
        return (block * 80 + distributionGroup * 20 + groupSlot) % popularTickerCount;
    }
    return popularTickerCount + ((block * 20 + groupSlot) % otherTickerCount);
}

function selectRange(iteration, tickerIndex) {
    const distributionBucket =
        tickerDistribution === "hot-80-20"
            ? iteration % 20
            : (Math.floor(iteration / tickers.length) + tickerIndex) % 20;
    if (distributionBucket < 14) {
        return ranges[0];
    }
    if (distributionBucket < 19) {
        return ranges[1];
    }
    return ranges[2];
}

function isSuccessfulCandlesResponse(response, minimumCandles) {
    if (response.status !== 200) {
        return false;
    }

    try {
        const body = response.json();
        return (
            body.success === true &&
            body.data !== null &&
            typeof body.data === "object" &&
            Array.isArray(body.data.candles) &&
            body.data.candles.length >= minimumCandles
        );
    } catch (_) {
        return false;
    }
}
