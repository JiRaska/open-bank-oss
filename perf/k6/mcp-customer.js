// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// MCP customer-channel baseline (ADR-0383 D5, test layer 6 of #16617).
//
// TWO QUESTIONS, ANSWERED SEPARATELY, because they fail separately:
//   1. tool_mix .......... how does the channel answer a realistic read-heavy tool-call mix when the
//                          caller stays UNDER its limit? (list_accounts / get_balance /
//                          list_transactions / propose_payment, weighted)
//   2. throttle_burst .... does the limiter fire at the CONFIGURED rate, with the limiter's own
//                          error, and is the rejection metered like any other deny? An over-limit
//                          burst that is admitted unthrottled, or answered with a different error,
//                          is a defect, not a slow sample.
//
// ORDERING (ADR-0383 D5). The decided order is: limiter BEFORE the grant lookup and BEFORE the
// policy decision, so a valid-token caller who floods denied calls is still bounded. Today
// McpEndpoint still runs the limiter AFTER the policy decision (#2409 ordering), so every
// throttled call still costs one PDP query. That is measurable: with MCP_PERF_PDP_METRICS_URL set
// to the OPA sidecar's /metrics, the burst records how many PDP queries the throttled calls cost.
// Recording is the default; MCP_PERF_EXPECT_LIMITER_BEFORE_POLICY=1 turns it into a hard gate
// that fails while the order is still policy-first. Do not set it until the ADR-0383 limiter
// lands, or the lane goes red for a known, tracked reason.
//
// SAFETY. The mix is read-only and the propose tool is refused by the unwired proposal store
// today (and only ever creates a REVIEWABLE proposal afterwards, never a debit). Run only against
// an isolated local or port-forwarded stack: the script refuses a non-localhost target unless
// MCP_PERF_ALLOW_NONLOCAL=1. The burst deliberately spends the test agent's per-minute window and
// part of its daily budget; use a dedicated test agent token and expect a pod roll to reset it.
//
// Required environment:
//   MCP_PERF_URL                 e.g. http://localhost:8150/mcp (the JSON-RPC endpoint)
//   MCP_PERF_AGENT_TOKEN         bearer for a TEST agent (sub "agent:<id>", with a consent_id claim)
//   MCP_PERF_ACCOUNT_ID          an account inside that agent's synthetic consent
// Optional:
//   MCP_PERF_LIMIT_PER_MINUTE    the service's configured calls-per-minute (default 60, the code
//                                default of openbank.mcp.rate-limit.calls-per-minute)
//   MCP_PERF_MIX_RATE            mix arrival rate in calls/second (default 0.8, i.e. 48/min, under 60)
//   MCP_PERF_METRICS_URL         MCP management metrics (default http://localhost:8085/q/metrics)
//   MCP_PERF_PDP_METRICS_URL     OPA sidecar /metrics, for the ordering measurement
//   MCP_PERF_ALLOW_NONLOCAL=1    allow a non-localhost MCP_PERF_URL (isolated port-forward only)
//   MCP_PERF_REQUIRE_METRICS=1   fail when the MCP metrics cannot be read (default: record and warn)
//   MCP_PERF_EXPECT_LIMITER_BEFORE_POLICY=1   hard-gate the ADR-0383 ordering (needs PDP metrics)
// Boolean flags accept only "1" (or unset/empty); any other value fails the run, so a typo such as
// "true" cannot silently turn a hard gate off.
// The token is never put in a tag, a log line or a summary. NEVER run this with --http-debug: it
// prints request headers, including the bearer token.
//
// STATUS OF THE ORDERING GATE: recorded-only by default, and it has NOT been run against a live
// OPA sidecar, only against a stand-in server. The per-admitted-call PDP cost is calibrated from a
// few calls before the burst (it is not assumed to be 1), but the metric name is unverified.
import http from "k6/http";
import { check, fail } from "k6";
import { Counter } from "k6/metrics";

function flag(name) {
  const v = __ENV[name];
  if (v === undefined || v === "") return false;
  if (v === "1") return true;
  return fail(`${name} must be "1" or unset, got a different value`);
}

http.setResponseCallback(http.expectedStatuses(200, 429));

const LIMIT = Number(__ENV.MCP_PERF_LIMIT_PER_MINUTE || 60);
const MIX_RATE = Number(__ENV.MCP_PERF_MIX_RATE || 0.8);
const BURST_CALLS = Math.ceil(LIMIT * 2.5);
const TARGET = (__ENV.MCP_PERF_URL || "").replace(/\/$/, "");
const METRICS_URL = __ENV.MCP_PERF_METRICS_URL || "http://localhost:8085/q/metrics";
const PDP_METRICS_URL = __ENV.MCP_PERF_PDP_METRICS_URL || "";
const ENFORCE_ORDER = flag("MCP_PERF_EXPECT_LIMITER_BEFORE_POLICY");
const REQUIRE_METRICS = flag("MCP_PERF_REQUIRE_METRICS");
const ALLOW_NONLOCAL = flag("MCP_PERF_ALLOW_NONLOCAL");
const CALIBRATION_CALLS = 5;

// The limiter's own answers. Today: HTTP 200 + JSON-RPC result.isError with this text (McpEndpoint
// "Rate limit exceeded: <reason>"). ADR-0383 D5 target: HTTP 429 + Retry-After. Either is the
// limiter speaking; anything else on an over-limit call is not.
const LIMITER_TEXT = "Rate limit exceeded: rate limit exceeded";
const DAILY_TEXT = "Rate limit exceeded: daily call budget exhausted";

// Weighted tool mix: a read-heavy retail assistant. Weights sum to 100.
const MIX = [
  { tool: "list_accounts", weight: 40 },
  { tool: "get_balance", weight: 30 },
  { tool: "list_transactions", weight: 25 },
  { tool: "propose_payment", weight: 5 },
];

const callsByTool = new Counter("mcp_calls_total");
const admitted = new Counter("mcp_admitted_total");
const throttled = new Counter("mcp_throttled_total");
const breaches = new Counter("mcp_contract_breaches");
const invalidSamples = new Counter("mcp_invalid_samples");
const orderViolations = new Counter("mcp_limiter_after_policy_violations");

export const options = {
  // Keep only non-identifying system tags; never let a URL, token or account id become a tag.
  systemTags: ["status", "method", "name", "check", "scenario", "expected_response"],
  scenarios: {
    tool_mix: {
      executor: "constant-arrival-rate",
      rate: MIX_RATE * 10,
      timeUnit: "10s",
      duration: "60s",
      preAllocatedVUs: 2,
      maxVUs: 4,
      exec: "toolMix",
      gracefulStop: "5s",
    },
    // Starts after the mix so the burst owns its own minute windows. The fixed-window limiter can
    // split a burst across clock-aligned windows; the assertions count the windows actually crossed.
    throttle_burst: {
      executor: "per-vu-iterations",
      vus: 1,
      iterations: 1,
      startTime: "70s",
      maxDuration: "120s",
      exec: "throttleBurst",
    },
  },
  thresholds: {
    // Hard: an unexpected outcome is a defect or an invalid sample, never a percentile.
    mcp_contract_breaches: ["count==0"],
    mcp_invalid_samples: ["count==0"],
    ...(ENFORCE_ORDER ? { mcp_limiter_after_policy_violations: ["count==0"] } : {}),
    // Advisory latency tripwires, like every perf threshold here (ADR-0243).
    "http_req_duration{scenario:tool_mix}": ["p(95)<500", "p(99)<1500"],
  },
};

export function setup() {
  if (!TARGET || !__ENV.MCP_PERF_AGENT_TOKEN || !__ENV.MCP_PERF_ACCOUNT_ID) {
    fail("MCP_PERF_URL, MCP_PERF_AGENT_TOKEN and MCP_PERF_ACCOUNT_ID are required");
  }
  if (!Number.isInteger(LIMIT) || LIMIT < 1 || LIMIT > 10000) fail("MCP_PERF_LIMIT_PER_MINUTE must be 1..10000");
  if (!(MIX_RATE > 0) || MIX_RATE * 60 >= LIMIT) {
    fail("MCP_PERF_MIX_RATE must stay under the per-minute limit, or the mix would measure throttling");
  }
  if (!ALLOW_NONLOCAL && !/^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/|$)/.test(TARGET)) {
    fail("MCP_PERF_URL must be a localhost origin for an isolated target");
  }
  if (ENFORCE_ORDER && !PDP_METRICS_URL) fail("MCP_PERF_EXPECT_LIMITER_BEFORE_POLICY=1 needs MCP_PERF_PDP_METRICS_URL");
  // A 401/403 or an empty consent invalidates the whole run: a rejection is not a handler sample.
  const probe = rpc("list_accounts", {});
  if (probe.status !== 200 || !isToolOk(probe)) {
    fail("setup probe: list_accounts did not succeed for the test agent (401/403, empty consent or downstream unavailable)");
  }
}

function headers() {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${__ENV.MCP_PERF_AGENT_TOKEN}`,
  };
}

function argsFor(tool) {
  const accountId = __ENV.MCP_PERF_ACCOUNT_ID;
  switch (tool) {
    case "get_balance":
      return { accountId };
    case "list_transactions":
      return { accountId, limit: 20 };
    case "propose_payment":
      // A syntactically valid synthetic proposal. Today it is refused (no proposal store); once
      // wired it creates a reviewable proposal only. The IBAN is a well-known valid example IBAN
      // (the Czech example from the IBAN registry), not a real customer account.
      return { fromAccountId: accountId, toIban: "CZ6508000000192000145399", amount: "1.00", currency: "CZK", reference: "perf" };
    default:
      return {};
  }
}

function rpc(tool, args) {
  return http.post(
    TARGET,
    JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: tool, arguments: args } }),
    { headers: headers(), tags: { name: "tools_call" } },
  );
}

function body(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function text(res) {
  const b = body(res);
  return b && b.result && b.result.content && b.result.content[0] ? String(b.result.content[0].text) : "";
}

function isToolOk(res) {
  const b = body(res);
  return !!(b && b.result && b.result.isError !== true && !b.error);
}

// True when the response is the limiter speaking, in today's shape or the ADR-0383 target shape.
function isLimiter(res) {
  if (res.status === 429) return true;
  const b = body(res);
  return !!(b && b.result && b.result.isError === true && text(res).startsWith("Rate limit exceeded"));
}

function pick() {
  let r = Math.random() * 100;
  for (const m of MIX) {
    if (r < m.weight) return m.tool;
    r -= m.weight;
  }
  return MIX[0].tool;
}

export function toolMix() {
  const tool = pick();
  const res = rpc(tool, argsFor(tool));
  callsByTool.add(1, { tool });
  if (isLimiter(res)) {
    // The mix stays under the limit by construction; a limiter answer means the window was already
    // spent (previous run, shared token) and the sample measures throttling, not the mix.
    invalidSamples.add(1, { reason: "mix-throttled" });
    return;
  }
  admitted.add(1);
  const ok = check(res, { "mix call answered 200": (r) => r.status === 200, "mix call is valid JSON-RPC": (r) => body(r) !== null });
  if (!ok) {
    breaches.add(1, { tool });
    return;
  }
  if (tool === "propose_payment") {
    // Propose-only by contract: never an executed payment. A refusal (unwired store) is expected.
    // Advisory tripwire only, not a proof: a keyword match cannot establish that nothing executed
    // (the real proof is the proposal store and ledger). Negated phrases ("not executed") are
    // stripped first so a correct refusal does not trip it.
    const t = text(res).toLowerCase().replace(/\b(not|never|no|isn't|wasn't|hasn't been|has not been|was not|is not)\s+(been\s+)?(executed|settled|debited|paid)\b/g, "");
    if (!check(res, { "propose never claims execution": () => !/\b(executed|settled|debited|paid)\b/.test(t) })) {
      breaches.add(1, { tool, kind: "propose-claims-execution" });
    }
    return;
  }
  if (!isToolOk(res)) {
    // A read tool failing is an invalid sample (unseeded consent, downstream down), not a latency.
    invalidSamples.add(1, { reason: "read-tool-error" });
  }
}

// ---- metrics scrape (Prometheus text format) ------------------------------------------------

function scrape(url) {
  if (!url) return null;
  const res = http.get(url, { tags: { name: "metrics_scrape" } });
  return res.status === 200 ? res.body : null;
}

function sumSeries(textBody, name, labelPredicate) {
  let total = 0;
  for (const line of textBody.split("\n")) {
    if (!line.startsWith(name)) continue;
    const rest = line.slice(name.length);
    if (rest[0] !== "{" && rest[0] !== " ") continue;
    const open = rest.indexOf("{");
    const close = rest.indexOf("}");
    const labels = open === 0 && close > 0 ? rest.slice(1, close) : "";
    if (labelPredicate && !labelPredicate(labels)) continue;
    const value = Number(line.slice(line.lastIndexOf(" ") + 1));
    if (Number.isFinite(value)) total += value;
  }
  return total;
}

const mcpDenied = (t) =>
  sumSeries(t, "openbank_mcp_tool_calls_total", (l) => l.includes('decision="DENY"') && l.includes('result="DENIED"'));
// OPA counts one HTTP request per policy query on its data API.
const pdpQueries = (t) =>
  sumSeries(t, "http_request_duration_seconds_count", (l) => l.includes('handler="v1/data"'));

export function throttleBurst() {
  const mcpBefore = scrape(METRICS_URL);
  const pdpBefore = scrape(PDP_METRICS_URL);
  if (mcpBefore === null && REQUIRE_METRICS) {
    fail("MCP_PERF_REQUIRE_METRICS=1 but the MCP metrics endpoint could not be read");
  }

  const bucket = () => Math.floor(Date.now() / 60000);
  const bucketBefore = bucket();
  let ok = 0;
  let limited = 0;
  // Calibrate the PDP cost of one ADMITTED call from a few calls before the burst, so the ordering
  // comparison does not assume one policy query per call.
  let calibrationOk = 0;
  for (let i = 0; i < CALIBRATION_CALLS; i++) {
    const res = rpc("list_accounts", {});
    callsByTool.add(1, { tool: "list_accounts" });
    if (isToolOk(res)) {
      calibrationOk++;
      admitted.add(1);
    } else {
      invalidSamples.add(1, { reason: "calibration-call-not-admitted" });
    }
  }
  const pdpCalibrated = scrape(PDP_METRICS_URL);
  ok += calibrationOk;
  let other = 0;
  let sawDaily = false;
  for (let i = 0; i < BURST_CALLS; i++) {
    const res = rpc("list_accounts", {});
    callsByTool.add(1, { tool: "list_accounts" });
    if (isLimiter(res)) {
      limited++;
      if (text(res).startsWith(DAILY_TEXT)) sawDaily = true;
      throttled.add(1);
      // Over the limit the answer must be the limiter's, in one of the two sanctioned shapes.
      const shaped = res.status === 429 || text(res).startsWith(LIMITER_TEXT) || text(res).startsWith(DAILY_TEXT);
      if (!check(res, { "throttled call carries the limiter error": () => shaped })) breaches.add(1, { kind: "wrong-limiter-shape" });
    } else if (isToolOk(res)) {
      ok++;
      admitted.add(1);
    } else {
      other++;
    }
  }

  // The configured rate. The limiter windows are clock-aligned,
  // so at most 2*LIMIT calls can be admitted and at least BURST_CALLS-2*LIMIT must be limited.
  if (sawDaily) invalidSamples.add(1, { reason: "daily-budget-exhausted" });
  if (other > 0) invalidSamples.add(1, { reason: "burst-non-limiter-error" });
  // Windows are clock-aligned (epoch seconds / 60), so the calls span at most
  // (minute buckets crossed + 1) windows, each admitting at most LIMIT.
  const windows = bucket() - bucketBefore + 1;
  const minLimited = BURST_CALLS - windows * LIMIT;
  const rate = check(null, {
    "admitted calls never exceed the limit times the windows the run spanned": () => ok <= LIMIT * windows,
    "the limiter fired on an over-limit burst": () => limited >= Math.max(1, minLimited),
  });
  if (!rate) breaches.add(1, { kind: "limiter-not-at-configured-rate" });

  // Throttled and denied calls are metered like any other deny (ADR-0383 D5).
  const mcpAfter = scrape(METRICS_URL);
  if (mcpBefore !== null && mcpAfter !== null) {
    const deniedDelta = mcpDenied(mcpAfter) - mcpDenied(mcpBefore);
    if (!check(null, { "every throttled call is metered as a DENY": () => deniedDelta >= limited })) {
      breaches.add(1, { kind: "throttle-not-metered" });
    }
  } else {
    console.warn("mcp metrics unreadable: throttle metering unverified");
  }

  // Ordering: how many PDP queries did the throttled calls cost? Zero when the limiter runs first.
  const pdpAfter = scrape(PDP_METRICS_URL);
  if (pdpBefore !== null && pdpCalibrated !== null && pdpAfter !== null) {
    const perCall = calibrationOk > 0 ? (pdpQueries(pdpCalibrated) - pdpQueries(pdpBefore)) / calibrationOk : 0;
    if (!(perCall > 0)) {
      invalidSamples.add(1, { reason: "pdp-calibration-failed" });
      return;
    }
    const burstAdmitted = ok - calibrationOk;
    const burstPdp = pdpQueries(pdpAfter) - pdpQueries(pdpCalibrated);
    // PDP queries beyond what the admitted calls account for were spent on throttled ones. Half a
    // call's cost is the tolerance, so a fractional per-call cost cannot create a phantom finding.
    const excess = burstPdp - burstAdmitted * perCall;
    const spentOnThrottled = excess >= perCall / 2 ? Math.round(excess / perCall) : 0;
    console.log(`ordering: pdp_per_admitted_call=${perCall} burst_admitted=${burstAdmitted} throttled=${limited} burst_pdp_queries=${burstPdp} pdp_calls_spent_on_throttled=${spentOnThrottled}`);
    if (spentOnThrottled > 0) orderViolations.add(1);
  } else if (PDP_METRICS_URL) {
    invalidSamples.add(1, { reason: "pdp-metrics-unreadable" });
  } else {
    console.warn("MCP_PERF_PDP_METRICS_URL unset: limiter-before-policy ordering not measured");
  }
}
