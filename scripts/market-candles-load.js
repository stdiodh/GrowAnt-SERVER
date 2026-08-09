import http from "k6/http";
import { check } from "k6";

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

const baseUrl = requiredEnvironmentVariable("BASE_URL").replace(/\/+$/, "");
const ticker = requiredEnvironmentVariable("TICKER");
const from = requiredEnvironmentVariable("FROM");
const to = requiredEnvironmentVariable("TO");
const token = __ENV.TOKEN;
const minCandles = positiveIntegerEnvironmentVariable("MIN_CANDLES", "1");

export const options = {
    vus: positiveIntegerEnvironmentVariable("VUS", "10"),
    duration: __ENV.DURATION || "30s",
    thresholds: {
        http_req_failed: ["rate<0.01"],
        http_req_duration: ["p(95)<200"],
        checks: ["rate>0.99"],
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

const url =
    `${baseUrl}/api/market/${encodeURIComponent(ticker)}/candles` +
    `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;

export default function () {
    const response = http.get(url, {
        headers,
        tags: { name: "/api/market/:ticker/candles" },
    });

    check(response, {
        "candles response is successful": isSuccessfulCandlesResponse,
    });
}

function isSuccessfulCandlesResponse(response) {
    if (response.status !== 200) {
        return false;
    }

    try {
        const candles = response.json("data.candles");
        return response.json("success") === true && Array.isArray(candles) && candles.length >= minCandles;
    } catch (_) {
        return false;
    }
}
