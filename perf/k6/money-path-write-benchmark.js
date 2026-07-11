// SPDX-License-Identifier: Apache-2.0
// k6 money-path WRITE benchmark (issue #669 scope item 1 — "money-path load benchmark").
//
// money-path-smoke.js is deliberately read-only ("Authenticated write baselines are a
// separate, carefully-scoped follow-up"). This is that follow-up: it drives real concurrent
// postings through account-service -> transaction-service -> [Temporal PaymentWorkflow] ->
// balance-service (hold) + ledger-service (journal), and records TPS + latency percentiles.
//
// LOCAL ONLY BY DESIGN. This mutates money-path state (creates accounts, posts real ledger
// journals) — it must never run against a shared cluster where other work depends on that
// data. Target the docker-compose stack (openbank-infra/docker-compose.yml), never the
// sandbox EKS PERF_LEDGER_URL/PERF_TXN_URL used by money-path-smoke.js's perf-baseline.yml.
//
// Prerequisites (see perf/reports/ for the exact command + versions used for the committed
// baseline numbers, and the real gaps this run surfaced):
//   cd openbank-infra && cp .env.example .env && docker compose up -d \
//     postgres kafka schema-registry keycloak opa temporal sanctions-service \
//     account-service balance-service ledger-service transaction-service
//   docker compose run --rm kafka-init   # topics must exist BEFORE any account is created —
//                                         # balance-service learns an account exists only via
//                                         # its account.opened Kafka consumer; an account
//                                         # created before kafka-init 404s on its first hold.
//   (temporal must be present — POST /api/v1/transactions blocks on a PaymentWorkflow that
//   never runs without it; sanctions-service must be present — account-service's screening
//   adapter fails CLOSED, unlike product-catalog's fail-open lookup, so account creation is
//   blocked without it. sanctions-service additionally needs AUTHZ_ENFORCE=false — matching
//   its real gitops deployment today — because OPA's rest.rego has no allow rule for a
//   resourceless M2M `sanctions.create` call yet, see the linked follow-up issue.)
//
// Run:
//   ACCOUNT_URL=http://localhost:8100 TXN_URL=http://localhost:8102 \
//   KEYCLOAK_URL=http://localhost:8080 \
//   k6 run perf/k6/money-path-write-benchmark.js
//
// Thresholds are ADVISORY, same convention as money-path-smoke.js (issue #334) — a breach is
// the tripwire to investigate, not a build failure; run with --no-thresholds-fail in CI.
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

const ACCOUNT_URL = __ENV.ACCOUNT_URL || "http://localhost:8100";
const TXN_URL = __ENV.TXN_URL || "http://localhost:8102";
const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || "http://localhost:8080";
// Dev-only confidential client, service-account role ROLE_OPERATOR (openbank-realm.json).
// NEVER a real credential — same value as openbank-infra/docker/keycloak/realm/openbank-realm.json.
const CLIENT_ID = __ENV.CLIENT_ID || "openbank-services";
const CLIENT_SECRET = __ENV.CLIENT_SECRET || "CHANGE_ME_LOCAL_DEV_ONLY";

// Independent-pairs pool: each VU picks two distinct accounts at random — models steady,
// uncontended traffic. Contention pool: deliberately tiny, so many concurrent VUs fight over
// the SAME few accounts' optimistic-lock version — models the exact class of bug the 2026-07
// test-stack audit found (lost updates, double-open, no-@Version races; issue #465/#527).
const INDEPENDENT_POOL_SIZE = Number(__ENV.INDEPENDENT_POOL_SIZE || 20);
const CONTENTION_POOL_SIZE = Number(__ENV.CONTENTION_POOL_SIZE || 3);
const FUNDING_AMOUNT = "1000000.00";
const TRANSFER_AMOUNT = "10.00";

const postLatency = new Trend("txn_post_ms", true);
const postSuccess = new Counter("txn_post_success");
const postFailure = new Counter("txn_post_failure");

// k6 v0.53+ exposes the WebCrypto API as a global (no import) — no remote jslib dependency,
// no hand-rolled Math.random() UUID (CodeQL correctly flags Math.random() feeding anything
// named like a UUID/token as js/insecure-randomness; crypto.randomUUID() is the real fix, not
// a suppression — idempotency keys benefit from genuine uniqueness under concurrent VUs too).
function uuidv4() {
  return crypto.randomUUID();
}

function getToken() {
  const res = http.post(
    `${KEYCLOAK_URL}/realms/openbank/protocol/openid-connect/token`,
    { grant_type: "client_credentials", client_id: CLIENT_ID, client_secret: CLIENT_SECRET },
    { headers: { "Content-Type": "application/x-www-form-urlencoded" } },
  );
  if (res.status !== 200) {
    throw new Error(`token request failed: ${res.status} ${res.body}`);
  }
  return res.json("access_token");
}

function openAccount(token) {
  const res = http.post(
    `${ACCOUNT_URL}/api/v1/accounts`,
    JSON.stringify({
      partyId: uuidv4(),
      // product-catalog is not part of this local subset — account-service's ProductCatalogPort
      // is fail-OPEN (issue #668/PR #727) so an unreachable lookup still allows account
      // creation; an arbitrary UUID here is intentional, not a shortcut around a real check.
      productId: uuidv4(),
      accountType: "CURRENT",
      // CZK, not EUR: PaymentJournalFactory's cash-clearing leg for a one-sided (non-
      // internal-transfer) payment is hardcoded to a CZK-only GL account regardless of the
      // transaction's actual currency — a real bug found while building this benchmark
      // (flagged separately, see perf/reports/ for the exact symptom). A non-CZK funding
      // CREDIT gets a 422 from ledger-service and the transaction ends FAILED. CZK is the
      // only currency the seeded GL chart of accounts actually supports end-to-end today.
      currencyCode: "CZK",
      legalName: "K6 Benchmark Account",
    }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        "Idempotency-Key": uuidv4(),
      },
    },
  );
  if (res.status !== 201) {
    throw new Error(`account creation failed: ${res.status} ${res.body}`);
  }
  return res.json("id");
}

function postTransaction(token, type, sourceAccountId, targetAccountId, amount) {
  const start = Date.now();
  const res = http.post(
    `${TXN_URL}/api/v1/transactions`,
    JSON.stringify({
      idempotencyKey: uuidv4(),
      type,
      sourceAccountId,
      targetAccountId,
      amount,
      currencyCode: "CZK",
      valueDate: new Date().toISOString().slice(0, 10),
      description: "k6 money-path write benchmark",
    }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      tags: { name: `txn_${type.toLowerCase()}` },
      timeout: "30s", // PaymentWorkflow round-trip, not a plain HTTP call — generous budget.
    },
  );
  postLatency.add(Date.now() - start);
  const ok = res.status === 201;
  ok ? postSuccess.add(1) : postFailure.add(1);
  check(res, { "transaction 201": () => ok });
  return ok;
}

export function setup() {
  const token = getToken();
  const fund = (id) => postTransaction(token, "CREDIT", null, id, FUNDING_AMOUNT);

  const independentAccounts = [];
  for (let i = 0; i < INDEPENDENT_POOL_SIZE; i++) {
    const id = openAccount(token);
    fund(id);
    independentAccounts.push(id);
  }

  const contentionAccounts = [];
  for (let i = 0; i < CONTENTION_POOL_SIZE; i++) {
    const id = openAccount(token);
    fund(id);
    contentionAccounts.push(id);
  }

  return { token, independentAccounts, contentionAccounts };
}

export const options = {
  scenarios: {
    // Uncontended baseline: each iteration picks a random distinct pair from a pool large
    // enough that two VUs rarely touch the same account at once.
    steady_writes: {
      executor: "ramping-vus",
      exec: "steadyWrites",
      startVUs: 1,
      stages: [
        { duration: "20s", target: 10 },
        { duration: "40s", target: 10 },
        { duration: "10s", target: 0 },
      ],
      gracefulStop: "15s",
    },
    // Deliberate contention: many VUs transferring between the SAME 3 accounts — every
    // posting races another for the same account's optimistic-lock version. Starts after
    // steady_writes finishes so the two don't confound each other's latency numbers.
    contention_writes: {
      executor: "ramping-vus",
      exec: "contentionWrites",
      startVUs: 1,
      startTime: "75s",
      stages: [
        { duration: "10s", target: 15 },
        { duration: "30s", target: 15 },
        { duration: "10s", target: 0 },
      ],
      gracefulStop: "15s",
    },
  },
  thresholds: {
    // Advisory tripwires — run with --no-thresholds-fail (money-path-smoke.js convention).
    "txn_post_ms": ["p(95)<3000"],
    "txn_post_failure": ["count<1"],
  },
};

// CodeQL's js/insecure-randomness taint tracking merges this pool index with the
// Idempotency-Key header built in the same postTransaction() call (same pattern as
// uuidv4() above) — crypto.getRandomValues() over Math.random() closes that flow too,
// not just satisfies the query literally.
function randomPoolIndex(length) {
  return crypto.getRandomValues(new Uint32Array(1))[0] % length;
}

function randomDistinctPair(pool) {
  const a = pool[randomPoolIndex(pool.length)];
  let b = pool[randomPoolIndex(pool.length)];
  while (b === a && pool.length > 1) {
    b = pool[randomPoolIndex(pool.length)];
  }
  return [a, b];
}

export function steadyWrites(data) {
  const [from, to] = randomDistinctPair(data.independentAccounts);
  postTransaction(data.token, "TRANSFER", from, to, TRANSFER_AMOUNT);
  sleep(0.2);
}

export function contentionWrites(data) {
  const [from, to] = randomDistinctPair(data.contentionAccounts);
  postTransaction(data.token, "TRANSFER", from, to, TRANSFER_AMOUNT);
}
