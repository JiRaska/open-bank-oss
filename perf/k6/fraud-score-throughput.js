// SPDX-License-Identifier: Apache-2.0
// k6 fraud-score THROUGHPUT baseline — step 0 of the fraud hot-path redesign.
//
// Measures today's POST /api/v1/fraud/score in openbank-fraud-service under an OFFERED load
// expressed in requests/s (ramping-arrival-rate), so later targets (thousands of decisions/s,
// p99 in low tens of ms) are set against a measured baseline rather than a guess. The number
// deliberately includes today's per-request Postgres work (threshold/velocity/payee reads plus
// the fraud_scores insert) — that cost is the subject, not noise.
//
// LOCAL ONLY BY DESIGN. Every request writes a fraud_scores row and advances velocity and
// new-payee state; it must never run against a shared sandbox or any environment whose fraud
// history matters. The script refuses a non-localhost FRAUD_URL unless
// FRAUD_PERF_ALLOW_NONLOCAL=1 is set explicitly.
//
// Prerequisites: an isolated stack with postgres, kafka, keycloak (openbank realm) and opa
// (plus redis/valkey if the service config requires it), e.g.
//   cd openbank-infra && cp .env.example .env && docker compose up -d postgres kafka keycloak opa
// and openbank-fraud-service booted against it (it has no docker-compose service today; run it
// in dev mode: ./gradlew :openbank-fraud-service:quarkusDev — HTTP port 8133).
//
// Run:
//   FRAUD_URL=http://localhost:8133 KEYCLOAK_URL=http://localhost:8080 \
//   k6 run --no-thresholds-fail perf/k6/fraud-score-throughput.js
// Select a single scenario:  FRAUD_PERF_SCENARIO=steady|ramp|hot-account
// Write a JSON summary:      FRAUD_PERF_REPORT=perf/reports/<date>-fraud-score-throughput.json
//
// Read dropped_iterations with every result: a non-zero count means the target (or the k6
// runner) could not keep up with the offered rate, so that rate was NOT sustained.
//
// Thresholds are ADVISORY (p99 < 150 ms is today's ADR-0084 budget) — a breach is the tripwire
// to investigate, not a build failure.
import http from "k6/http";
import { check, fail } from "k6";
import { Trend, Counter, Rate } from "k6/metrics";

const FRAUD_URL = __ENV.FRAUD_URL || "http://localhost:8133";
const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || "http://localhost:8080";
// Dev-only confidential client from openbank-infra/docker/keycloak/realm/openbank-realm.json.
// NEVER a real credential.
const CLIENT_ID = __ENV.CLIENT_ID || "openbank-services";
const CLIENT_SECRET = __ENV.CLIENT_SECRET || "CHANGE_ME_LOCAL_DEV_ONLY";

const LOCAL_ORIGIN = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?$/;
if (__ENV.FRAUD_PERF_ALLOW_NONLOCAL !== "1") {
  if (!LOCAL_ORIGIN.test(FRAUD_URL)) {
    throw new Error("FRAUD_URL must be a localhost origin (set FRAUD_PERF_ALLOW_NONLOCAL=1 for an isolated non-local target)");
  }
  if (!LOCAL_ORIGIN.test(KEYCLOAK_URL)) {
    throw new Error("KEYCLOAK_URL must be a localhost origin (set FRAUD_PERF_ALLOW_NONLOCAL=1 to override)");
  }
}

const num = (name, dflt) => {
  const v = Number(__ENV[name] || dflt);
  if (!Number.isFinite(v) || v <= 0) throw new Error(`${name} must be a positive number`);
  return v;
};

const STEADY_RPS = num("FRAUD_PERF_STEADY_RPS", 200);
const STEADY_DURATION = __ENV.FRAUD_PERF_STEADY_DURATION || "2m";
const MAX_RPS = num("FRAUD_PERF_MAX_RPS", 2000);
const RAMP_STEPS = num("FRAUD_PERF_RAMP_STEPS", 5);
const RAMP_STEP_DURATION = __ENV.FRAUD_PERF_RAMP_STEP_DURATION || "1m";
const HOT_RPS = num("FRAUD_PERF_HOT_RPS", 200);
const HOT_DURATION = __ENV.FRAUD_PERF_HOT_DURATION || "2m";
const ACCOUNT_POOL_SIZE = num("FRAUD_PERF_ACCOUNT_POOL", 10000);
const HOT_POOL_SIZE = num("FRAUD_PERF_HOT_POOL", 3);
const COUNTERPARTY_POOL_SIZE = num("FRAUD_PERF_COUNTERPARTY_POOL", 2000);
const MAX_VUS = num("FRAUD_PERF_MAX_VUS", 2000);
// Share of requests above the large-single-transaction threshold (seed: CZK 500000, EUR 20000).
const LARGE_SHARE = Number(__ENV.FRAUD_PERF_LARGE_SHARE || 0.05);

const rampStages = [];
for (let i = 1; i <= RAMP_STEPS; i++) {
  const target = Math.round((MAX_RPS * i) / RAMP_STEPS);
  rampStages.push({ target, duration: "10s" }); // step up
  rampStages.push({ target, duration: RAMP_STEP_DURATION }); // hold
}

// Scenarios run sequentially (startTime offsets) so one never contaminates another.
const allScenarios = {
  steady: {
    executor: "ramping-arrival-rate",
    exec: "independent",
    startRate: STEADY_RPS,
    timeUnit: "1s",
    preAllocatedVUs: Math.min(MAX_VUS, Math.max(50, STEADY_RPS)),
    maxVUs: MAX_VUS,
    stages: [{ target: STEADY_RPS, duration: STEADY_DURATION }],
    tags: { scenario_kind: "steady" },
  },
  ramp: {
    executor: "ramping-arrival-rate",
    exec: "independent",
    startRate: 0,
    timeUnit: "1s",
    preAllocatedVUs: Math.min(MAX_VUS, 200),
    maxVUs: MAX_VUS,
    stages: rampStages,
    tags: { scenario_kind: "ramp" },
  },
  "hot-account": {
    executor: "ramping-arrival-rate",
    exec: "hotAccount",
    startRate: HOT_RPS,
    timeUnit: "1s",
    preAllocatedVUs: Math.min(MAX_VUS, Math.max(50, HOT_RPS)),
    maxVUs: MAX_VUS,
    stages: [{ target: HOT_RPS, duration: HOT_DURATION }],
    tags: { scenario_kind: "hot-account" },
  },
};

function selectScenarios() {
  const only = __ENV.FRAUD_PERF_SCENARIO;
  if (only) {
    if (!allScenarios[only]) throw new Error(`unknown FRAUD_PERF_SCENARIO ${only}`);
    return { [only]: allScenarios[only] };
  }
  // Sequential: steady, then ramp, then hot-account, each offset by the previous total + 30s gap.
  const toSec = (d) => {
    const m = /^(\d+)(s|m|h)$/.exec(d);
    if (!m) throw new Error(`unsupported duration ${d} (use Ns|Nm|Nh)`);
    return Number(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
  };
  const steadyEnd = toSec(STEADY_DURATION) + 30;
  const rampEnd = steadyEnd + RAMP_STEPS * (10 + toSec(RAMP_STEP_DURATION)) + 30;
  return {
    steady: { ...allScenarios.steady, startTime: "0s" },
    ramp: { ...allScenarios.ramp, startTime: `${steadyEnd}s` },
    "hot-account": { ...allScenarios["hot-account"], startTime: `${rampEnd}s` },
  };
}

export const options = {
  scenarios: selectScenarios(),
  summaryTrendStats: ["avg", "min", "med", "p(50)", "p(90)", "p(95)", "p(99)", "p(99.9)", "max"],
  thresholds: {
    fraud_score_ms: ["p(99)<150"],
    fraud_non_200: ["rate<0.01"],
    dropped_iterations: ["count==0"],
  },
};

const scoreLatency = new Trend("fraud_score_ms", true);
const verdictAllow = new Counter("fraud_verdict_allow");
const verdictReview = new Counter("fraud_verdict_review");
const verdictChallenge = new Counter("fraud_verdict_challenge");
const verdictDecline = new Counter("fraud_verdict_decline");
const verdictOther = new Counter("fraud_verdict_other");
const scoreFailure = new Counter("fraud_score_failure");
const non200 = new Rate("fraud_non_200");

const RAILS = ["DOMESTIC", "SEPA", "SCT_INST", "CARD"];

export function setup() {
  const res = http.post(
    `${KEYCLOAK_URL}/realms/openbank/protocol/openid-connect/token`,
    { grant_type: "client_credentials", client_id: CLIENT_ID, client_secret: CLIENT_SECRET },
    { headers: { "Content-Type": "application/x-www-form-urlencoded" } },
  );
  if (res.status !== 200) fail(`token request failed: ${res.status}`);
  const pool = (n) => Array.from({ length: n }, () => crypto.randomUUID());
  // Token lifetime: a long run may outlive it; a burst of 401s shows up in fraud_non_200.
  return {
    token: res.json("access_token"),
    accounts: pool(ACCOUNT_POOL_SIZE),
    hotAccounts: pool(HOT_POOL_SIZE),
    counterparties: pool(COUNTERPARTY_POOL_SIZE),
  };
}

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

function currency() {
  const r = Math.random();
  if (r < 0.8) return "CZK";
  if (r < 0.97) return "EUR";
  return "USD"; // always REVIEW today — visible in fraud_verdict_review
}

function amount(cur) {
  const large = Math.random() < LARGE_SHARE;
  const threshold = cur === "CZK" ? 500000 : 20000;
  const value = large ? threshold * (1 + Math.random()) : 1 + Math.random() * (threshold / 50);
  return value.toFixed(2);
}

function score(data, accountPool) {
  const cur = currency();
  const body = JSON.stringify({
    amount: amount(cur),
    currency: cur,
    rail: RAILS[__ITER % RAILS.length],
    accountId: pick(accountPool),
    counterpartyId: pick(data.counterparties),
  });
  const res = http.post(`${FRAUD_URL}/api/v1/fraud/score`, body, {
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${data.token}` },
    tags: { name: "fraud_score" },
  });
  scoreLatency.add(res.timings.duration);
  non200.add(res.status !== 200);
  const ok = check(res, { "score 200": (r) => r.status === 200 });
  if (!ok) {
    scoreFailure.add(1);
    return;
  }
  let verdict;
  try {
    verdict = res.json("verdict");
  } catch (_) {
    verdict = undefined;
  }
  switch (verdict) {
    case "ALLOW": verdictAllow.add(1); break;
    case "REVIEW": verdictReview.add(1); break;
    case "CHALLENGE": verdictChallenge.add(1); break;
    case "DECLINE": verdictDecline.add(1); break;
    default: verdictOther.add(1);
  }
}

export function independent(data) {
  score(data, data.accounts);
}

export function hotAccount(data) {
  score(data, data.hotAccounts);
}

export function handleSummary(data) {
  const out = { stdout: JSON.stringify(data.metrics, null, 2) + "\n" };
  const report = __ENV.FRAUD_PERF_REPORT;
  if (report) {
    if (!/^perf\/reports\/[\w.-]+\.json$/.test(report)) {
      throw new Error("FRAUD_PERF_REPORT must be perf/reports/<name>.json");
    }
    out[report] = JSON.stringify(data, null, 2);
  }
  return out;
}
