// SPDX-License-Identifier: Apache-2.0
// k6 security abuse lane (ADR-0279 WS1 #4).
//
// THE OTHER DIRECTION OF A LOAD TEST. Every existing k6 scenario measures how fast a VALID
// request is served; this lane measures how correctly an INVALID one is rejected. An
// authentication boundary that answers a garbage token with a 500, a detail endpoint that
// answers an enumeration sweep with a 200, or a query parameter that lets a NUL byte reach
// the database are all security defects — and each has shipped here before (NulByteGuards,
// the non-null JAX-RS 500 class). Nothing ran them as a load-shaped probe until now.
//
// WHY THIS LANE IS INHERENTLY SAFE TO SCHEDULE. Every request it sends is invalid by
// construction — no valid token is ever used, no body is ever accepted, nothing is ever
// created. The whole lane is the rejection path, so it needs no isolated money-path target
// and no synthetic-data teardown (the two blockers that keep money-path-write-benchmark
// manual). A 200 on any of these probes is not a success: it is the finding.
//
// What each scenario asserts (the expected-status contract, per probe class):
//   no-token probe .......... 401/403 — never 200, never 5xx
//   malformed-token probe ..... 401/403 — a garbage Bearer must not 500 (JWT parse robustness)
//   NUL-byte query param ...... 400/401/403 — the fleet NulByteGuards boundary; 500 = regression
//   enumeration sweep ......... 401/403/404 across sequential ids — 200 = IDOR, and the
//                               response-time spread is recorded so a timing side-channel
//                               (existing id answers slower) is at least visible in the trend
//   oversized-header probe .... 400/401/431 — never 5xx
//
// Run against any booted service:
//   BASE_URL=http://localhost:8101 ABUSE_PATH=/api/v1/info DETAIL_PATH_TEMPLATE=/api/v1/products/{id} \
//     k6 run perf/k6/security-abuse-smoke.js
//
// Thresholds are ADVISORY like every perf threshold here (ADR-0243): the CI lane records,
// Test Intelligence retains the breach. What is NOT advisory is the status contract — a
// rejected probe that answers 200 or 5xx fails the run outright, because that is a defect,
// not a regression.
import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8101";
// A known-protected read endpoint of the service under test.
const ABUSE_PATH = __ENV.ABUSE_PATH || "/api/v1/info";
// A detail endpoint template with {id} for the enumeration sweep. Defaults to a generic
// guess; services override per env. The ids probed are deliberately non-existent.
const DETAIL = __ENV.DETAIL_PATH_TEMPLATE || `${ABUSE_PATH}/{id}`;

const REJECT = { "status contract holds": (r) => [400, 401, 403, 404, 429, 431].includes(r.status) };
const NO_SUCCESS = { "never 200": (r) => r.status !== 200 };
const NO_5XX = { "never 5xx": (r) => r.status < 500 };

const probes = new Counter("security_probes_total", true);
const contractBreaches = new Counter("security_contract_breaches", true);
const enumerateMs = new Trend("enumeration_ms", true);

function probe(name, response) {
  probes.add(1, { probe: name });
  const ok = check(response, REJECT) && check(response, NO_SUCCESS) && check(response, NO_5XX);
  if (!ok) contractBreaches.add(1, { probe: name });
}

export const options = {
  scenarios: {
    // Unauthenticated + malformed-identity probes: steady low rate — correctness, not load.
    identity_rejection: {
      executor: "constant-vus",
      vus: 2,
      duration: "30s",
      exec: "identityRejection",
    },
    // The enumeration sweep: sequential ids in a burst, the shape a scraper makes.
    enumeration_sweep: {
      executor: "per-vu-iterations",
      vus: 3,
      iterations: 20,
      startTime: "35s",
      exec: "enumerationSweep",
    },
  },
  thresholds: {
    // The one hard gate in this lane: a single contract breach (200 or 5xx on an invalid
    // request) fails the run.
    security_contract_breaches: ["count==0"],
    // Advisory tripwires, same status as every perf threshold in this repo.
    enumeration_ms: ["p(95)<800"],
  },
};

export function identityRejection() {
  // No token at all.
  probe("no-token", http.get(`${BASE_URL}${ABUSE_PATH}`));

  // A structurally invalid Bearer — must be rejected as cleanly as none.
  probe(
    "malformed-token",
    http.get(`${BASE_URL}${ABUSE_PATH}`, {
      headers: { Authorization: "Bearer not-a-jwt.not-a-jwt.not-a-jwt" },
    }),
  );

  // NUL byte percent-encoded into a query parameter — the NulByteGuards boundary. A 500
  // here means a decoded U+0000 reached a layer it never should (SQLState 22021 class).
  probe("nul-byte-param", http.get(`${BASE_URL}${ABUSE_PATH}?probe=%00`));

  // An absurdly long header value — parser robustness, must 400/401/431 rather than 5xx.
  probe(
    "oversized-header",
    http.get(`${BASE_URL}${ABUSE_PATH}`, { headers: { "X-Probe": "A".repeat(8192) } }),
  );
}

export function enumerationSweep() {
  // Sequential, non-existent ids. 401/403/404 are all correct rejections; a 200 is an
  // IDOR finding. The latency trend makes a found-vs-not-found timing gap visible.
  const base = 1000000 + Math.floor(Math.random() * 1000);
  const id = base + __iter;
  const res = http.get(`${BASE_URL}${DETAIL.replace("{id}", String(id))}`);
  enumerateMs.add(res.timings.duration);
  probe("enumeration", res);
}
