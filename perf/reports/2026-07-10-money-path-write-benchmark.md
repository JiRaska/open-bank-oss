# Money-path write benchmark — 2026-07-10

Issue #669 scope item 1 ("money-path load benchmark"). First **write** baseline — the existing
`perf/k6/money-path-smoke.js` is deliberately read-only. This run drives real concurrent postings
through `account-service -> transaction-service -> [Temporal PaymentWorkflow] -> balance-service
(hold) + ledger-service (journal)` and reports throughput/latency plus what broke along the way.

**Local only, by design.** This mutates money-path state (creates accounts, posts real ledger
journals). It ran against a local `docker-compose` stack, never the shared sandbox EKS cluster —
out of scope for this pass (deferred, per the explicit scope decision on issue #669).

## TL;DR

- **0 transaction failures** across two runs (2,197 + 469 = 2,666 postings), including a dedicated
  contention scenario that deliberately hammers 3 accounts with 15 concurrent VUs. Correctness held.
- Getting a *single* write to complete locally required fixing **five** previously-latent defects —
  nobody had run this write path end-to-end on a clean local checkout before. Those are the more
  important finding of this exercise; see "What broke" below.
- Throughput/latency numbers are **not stable across repeated runs on this machine** — see
  "Numbers" and the honesty note below. Treat them as an order-of-magnitude baseline, not a
  precise SLA figure, and treat the *investigation* they triggered as the actual deliverable.

## Environment (exact, for reproducibility)

| | |
|---|---|
| Host | Apple M2 Max, 12 cores, 32 GiB RAM, macOS 26.4.1 |
| Container runtime | OrbStack, Docker server 29.4.0 |
| JDK | Temurin 25.0.3+9 (host-run services — see "How this was actually run") |
| k6 | v2.1.0 (darwin/arm64) |
| Repo commit | `5431d9829b63f2b13b4dea2ebc0052136221592d` (branch `perf/money-path-write-benchmark`, based on `origin/main`) |
| Service versions | account-service 0.13.0, balance-service 1.8.3, ledger-service 1.10.4, transaction-service 1.13.1, sanctions-service 0.6.5 |
| Temporal | `temporalio/temporal:1.7.3`, dev-mode single binary, in-memory (new — see "What broke") |

This is a single shared development laptop, not isolated benchmark hardware — other processes
(IDE, browser, this very Claude Code session) were running concurrently. Numbers have correspondingly
wide error bars; a real capacity baseline needs dedicated hardware or the sandbox EKS cluster
(explicitly out of scope for this pass).

## How this was actually run

`docker-compose.yml`'s own `standalone-service.Dockerfile` builds each service **inside Docker**
via `./gradlew quarkusBuild` from a cold cache. That path OOM'd the container runtime after ~40
minutes across 4 parallel builds (`rpc error: code = Unavailable desc = error reading from server:
EOF` — a BuildKit connectivity loss, matching this repo's own documented "Parallel Docker/Gradle
builds OOM" pitfall, just never hit from a **cold** Gradle cache before). Fell back to building the
uber-jars host-side with a warm `~/.gradle` cache (45s for 4 services) and running them as plain
`java -jar` processes against the same dockerized infra (Postgres/Kafka/Keycloak/OPA/Temporal/Valkey).
This is *not* how CI or production builds these images — it was the pragmatic path to get a working
local write-path stack without burning another 40 minutes per retry. `build-push-service.sh`
(host-side build, then `docker build` with a plain `COPY`) is still the right pattern for anything
that needs an actual container image.

## What broke (the real finding)

Every one of these was a genuine, previously-undiscovered defect — not a benchmark-script bug —
found only because this was, as far as I can tell, the first time anyone ran the full write path
against a clean local checkout:

1. **Temporal was entirely absent from `docker-compose.yml`.** `POST /api/v1/transactions` blocks
   on a `PaymentWorkflow` that never runs without it — every local write silently hung. Added a
   dev-mode `temporalio/temporal:1.7.3` container (`temporal server start-dev`, in-memory, zero
   extra Postgres/Cassandra) and wired `transaction-service`'s `TEMPORAL_SERVER_URL`.
2. **`account-service`'s sanctions-service client pointed at the wrong port** (`:8110`, actually
   sca-service's port; sanctions-service listens on `:8123`). Sanctions screening fails **closed**
   (unlike product-catalog's fail-open lookup), so every local account creation was silently
   blocked, not just slow. Fixed the wrong fallback default and added an explicit compose override.
3. **22 of 27 service blocks in `docker-compose.yml` hardcode a stale literal Postgres password**
   instead of `${POSTGRES_PASSWORD}` (from `.env`, correctly used by only 5 services). Confirmed by
   testing real peer-to-peer auth over the Docker network, not the
   trust-auth-by-default `docker exec` shortcut. This meant a fresh `docker compose up` following
   the documented `.env.example` recipe could never have booted most of the local stack. Same
   pattern, same fix, for 18 hardcoded Valkey passwords. Bulk sed-fixed across the whole file.
4. **OPA's `rest.rego` has no rule authorizing the M2M `sanctions.create` call at all.** A
   service-account token is classified `HUMAN` (documented quirk, see the file's own comment on
   `edge-service-notification`), and the only rule that would grant a HUMAN+`ROLE_OPERATOR` caller
   requires a resource-scoped action — `sanctions.create` has none. Confirmed by direct testing:
   `403 {"code":"FORBIDDEN","message":"policy denied"}`. Not a live incident — the real gitops
   deployment already runs sanctions-service with `AUTHZ_ENFORCE=false` (advisory-only), which
   masks it — but it's a **blocking pre-condition** for that gate ever graduating to enforced.
   Local dev was simply never told about that `false`; added the matching override. Deeper policy
   fix flagged as a separate follow-up (not safe to rush a same-day OPA authz change).
5. **`PaymentJournalFactory`'s cash-clearing leg is hardcoded to a CZK-only GL account** regardless
   of the transaction's actual currency. A same-currency, one-sided (non-internal-transfer) payment
   in any other currency — confirmed with EUR — gets `422` from `ledger-service` and the whole
   transaction ends `FAILED` after the `LedgerCallGuard` circuit breaker trips. Every account/amount
   in this benchmark uses CZK to avoid it. Flagged as a separate follow-up (real accounting-chart
   design work, money-path domain logic, needs the 2-approval + threat-model process).
6. **Kafka topics must exist *before* the first account is created.** `KAFKA_AUTO_CREATE_TOPICS_ENABLE:
   false` + `kafka-init` never having been run meant `balance-service` never learned an account
   existed (no `account.opened` consumption) — its first `placeHold` call 404'd. Not a code bug,
   just an easy-to-miss local setup step; documented explicitly in the k6 script's header now.

None of these are exotic — every one is the "nobody ever actually ran this locally" class of bug
this repo's own CLAUDE.md has hit repeatedly (stale ktlint wildcards, wrong podmonitor namespace
list, etc.). That pattern held here too, just for the write path as a whole rather than one file.

## Numbers

**Run 1** (clean, no per-request trace export — the summary-only path):

| Metric | Value |
|---|---|
| Total postings | 2,197 (0 failed) |
| Achieved throughput | ~16.7 req/s (`steady_writes`: 10 VUs uncontended; `contention_writes`: 15 VUs on 3 shared accounts) |
| Latency (all postings) | avg 435ms · p50 251ms · p90 345ms · p95 2.25s · max 6.35s |

**Run 2** (identical script, run immediately after run 1, with full per-request JSON trace export
enabled via `k6 run --out json=...`):

| Metric | Value |
|---|---|
| Total postings | 469 (0 failed) — k6 issued far fewer requests in the same wall-clock budget |
| Latency (`steady_writes`, uncontended) | p50 250ms · p90 **6.15s** · p95 **6.17s** — a step function, not gradual drift |
| Latency (`contention_writes`, 15 VUs / 3 accounts) | median already at **6.10s**, i.e. saturated from the start |

**Honesty note on the run-to-run gap.** Run 2 is dramatically worse and I have not root-caused why
with confidence. Working hypotheses, in rough order of likelihood, none confirmed:
- Several services run with `QUARKUS_DATASOURCE_JDBC_MAX_SIZE: 5` (a deliberately small local-dev
  pool) — 5 connections shared across 10–15 concurrent VUs plausibly queues under load.
- Temporal's dev-mode server is single-node and in-memory; by run 2 it had accumulated workflow
  history from ~2,200 prior executions with no restart in between.
- k6's own `--out json` write path adds overhead that competes with the JVM services for host
  CPU on a single shared laptop with ~9 concurrent JVM processes already running.

I'm reporting both runs rather than picking the flattering one. The **repeatable, load-sensitive
degradation itself** is the more useful signal than either individual number — it's exactly the
"contention behaviour worth measuring, not just peak TPS" issue #669 asked for, and it surfaced on
the very first attempt to measure it. A real capacity conclusion needs: isolated hardware, JDBC
pool tuning experiments, and Temporal server metrics — none of which are in scope for this pass.

## Re-running this

```bash
cd openbank-infra
cp .env.example .env
docker compose up -d postgres kafka schema-registry keycloak opa temporal sanctions-service \
  account-service balance-service ledger-service transaction-service
docker compose run --rm kafka-init   # MUST run before the first account is created

ACCOUNT_URL=http://localhost:8100 TXN_URL=http://localhost:8102 KEYCLOAK_URL=http://localhost:8080 \
  k6 run perf/k6/money-path-write-benchmark.js
```

If `docker compose up --build` OOMs on a cold Gradle cache (see "How this was actually run" above),
build host-side first: `./gradlew :openbank-account-service:quarkusBuild
:openbank-balance-service:quarkusBuild :openbank-ledger-service:quarkusBuild
:openbank-transaction-service:quarkusBuild :openbank-sanctions-service:quarkusBuild
-Dquarkus.package.jar.type=uber-jar`, then run the resulting jars with `java -jar`, pointing every
`QUARKUS_DATASOURCE_*_URL` / `KAFKA_BOOTSTRAP_SERVERS` / `QUARKUS_OIDC_AUTH_SERVER_URL` /
`QUARKUS_REDIS_HOSTS` at `localhost` instead of the compose service DNS names, plus
`SANCTIONS_SERVICE_URL=http://localhost:8123` for account-service and
`TEMPORAL_SERVER_URL=localhost:7233` for transaction-service.

## Deferred (explicit scope decision on issue #669)

- Running this (or a heavier variant) against the shared sandbox EKS cluster, with a documented
  node shape.
- The chaos/DR drill (kill a money-path pod mid-flow, force a Postgres failover).

## Linked follow-ups filed from this run

- OPA `rest.rego` M2M `sanctions.create` authorization gap (item 4 above).
- `PaymentJournalFactory` non-CZK cash-clearing leg (item 5 above).
