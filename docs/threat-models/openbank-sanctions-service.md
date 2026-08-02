<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-sanctions-service

STRIDE/DFD threat model for the sanctions/PEP screening bounded context, per ADR-0030 D2.
Money-path service — `SANCTIONS_SCREENING` in the KYC check set (ADR-0116) delegates here, and
ADR-0032 documents the synchronous AML/sanctions screening gate. Reviewed in PR.

- **Status:** Draft (first pass, written alongside the real-feed-registration wiring; §6 added
  with the review-path persistence fix)
- **Last reviewed:** 2026-08-02
- **Owner:** sanctions/AML CODEOWNERS
- **Related ADRs:** ADR-0002 (hexagonal), ADR-0032 (synchronous AML/sanctions screening gate),
  ADR-0100 (injected Clock, Layer 1 determinism), ADR-0116 (KYC engine — `SANCTIONS_SCREENING`
  check delegates to this service), ADR-0030 (money-path threat models),
  ADR-0126 (assigned-id persistence), ADR-0155 (four-eyes gate)

## 1. Scope & assets

The sanctions service maintains the bank's screening data set — sanctioned/PEP entities from
external authoritative feeds — and answers name/entity match queries used by KYC and payment
screening gates. A false negative (a sanctioned entity not matched) is a direct AML/regulatory
failure; a false positive at scale creates an operational/customer-harm burden but is the safer
failure direction.

Assets protected, in priority order:

1. **`sanctions_entries` data set** — the imported screening data itself. Staleness, corruption,
   or a silently-empty import directly degrades the bank's AML control (a false negative if a
   newly-sanctioned entity is never imported).
2. **`sanctions_lists` registry** (`source_url`, `enabled`, cron schedule) — determines *what* gets
   imported and *when*. An attacker (or an operator mistake) who disables a list or repoints
   `source_url` to a non-authoritative endpoint silently blinds a whole screening category.
3. **Match/query results** (`SanctionsResource`) — consumed by KYC/payment callers to make a
   screening decision; a tampered or spoofed response is a direct false ALLOW.
4. **Import provenance** — which URL was fetched, when, and how many entries were upserted
   (`last_updated_at`, `last_entry_count`) — the audit trail for "was the fleet's sanctions data
   current when a given screening decision was made."

## 2. Data-flow diagram (textual)

```
                          ┌────────────────── trust boundary: sanctions-service ─────────────────────┐
[KYC / payment callers]   │                                                                            │
ROLE_SERVICE  ──1──┼──────┼─▶ REST (SanctionsResource)  ──▶ SanctionsService ──▶ SanctionsEntryRepository │
  JWT (Keycloak)    │                                            (use case)         (pg_trgm similarity)  │
                    │                                                                    │                │
[Operator/Admin] ─2─┼──────┼─▶ REST (SanctionsListResource) ──▶ SanctionsListService ────┼──▶ [Postgres] ─3─│
  JWT @RolesAllowed │           GET/PUT list, POST refresh          │                  sanctions_entries    │
                    │                                               ▼                  sanctions_lists     │
[External feeds] ─4─┼──────────────────────────────────────▶ SanctionsImportService                        │
  OFAC (Treasury),  │      @Scheduled(60s) scheduledRefresh()  (SAX/CSV streaming, no auth on egress side)  │
  OpenSanctions.org │                                                                                       │
                    └───────────────────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries crossed: (1) external caller → REST match query; (2) operator → REST list
management/manual refresh; (3) service → Postgres; (4) service → external feed hosts (`treasury.gov`
/ `sanctionslistservice.ofac.treas.gov`, `data.opensanctions.org`) — the one **outbound** trust
boundary this service has that most others don't: it fetches and parses attacker-reachable content
from the public internet on a timer, unauthenticated, with no response signing/verification beyond
TLS. Domain layer (`SanctionsEntry`, `SanctionsList` models) has zero framework imports (ADR-0002).

## 3. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST in (match/list) | **Spoofing** — caller forges identity to obtain a screening result or mutate the list registry | Bearer JWT (Keycloak); `@RolesAllowed` on every endpoint (`ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_SERVICE` split by sensitivity — mutation restricted to `ROLE_OPERATOR`/`ROLE_ADMIN`); `@Authorize` (OPA) on every method | OPA fine-grained authz is advisory in most of the fleet — verify enforce status before prod go-live |
| T1 | `sanctions_lists.source_url` | **Tampering** — an operator (or a compromised operator credential) repoints a list's `source_url` to a non-authoritative or attacker-controlled host; the next scheduled/manual refresh imports whatever that host serves, silently corrupting the screening data set | `update()` is `ROLE_OPERATOR`/`ROLE_ADMIN` only; every change goes through the standard JWT/RBAC gate; `SanctionsImportService` never buffers/executes fetched content as code (SAX/CSV parse only, never eval) | **No allowlist of source hosts enforced in code today** — an authorized operator can point `source_url` anywhere. Accepted for sandbox; a host allowlist (OFAC/OpenSanctions domains only) is a recommended hardening before prod (tracked as an open item below) |
| T2 | Import pipeline | **Tampering** — a MITM or compromised upstream (Treasury, OpenSanctions) serves altered list content | TLS on all fetch URLs (`https://`); `HttpClient` default trust store validates certs; no additional payload signature/checksum verification | No content-integrity check (e.g. a published SHA-256 manifest) beyond TLS — accepted; OFAC does publish a `Digest` response header (unused today) |
| T3 | `sanctions_entries` rows | **Tampering** — direct DB mutation bypassing the import pipeline | App-only write path via `SanctionsEntryRepository.upsertAll`/`deactivateByListType`; reactive Panache; forward-only Flyway | DB-admin insider — infra scope |
| R1 | Import outcome | **Repudiation** — dispute over which source URL and entry count backed a screening decision at a given time | `sanctions_lists.last_updated_at`/`last_entry_count` updated on every successful refresh (manual and scheduled); `SanctionsImportService` logs source URL + entry count per run | Not yet a signed/immutable audit record (ADR-0029 evidence bundle) — *planned*, same gap as fraud-service R1 |
| I1 | Match results | **Information disclosure** — sanctions/PEP match reasons could leak screening logic or PII beyond what the caller needs | Match responses are same-list domain data already public on the source feed (sanctioned entity names); endpoint role-gated, no customer-facing exposure | Low — screening data is inherently about named public entities |
| D1 | Scheduled refresh | **DoS (self-inflicted)** — a slow or hung upstream feed (e.g. a stalled multi-hundred-MB CSV stream) blocks the refresh loop or exhausts memory | Streaming parsers (SAX for OFAC XML, `BufferedReader` line-by-line for OpenSanctions CSV) — **O(1) peak memory per entry**, never buffers the full body; `IMPORT_BATCH_SIZE = 500` batched upserts; per-list `try/catch` in `scheduledRefresh()` — one hung/failing list logs a warning and does not block the others; `HttpClient` has a 30s connect timeout and 300s request timeout | No circuit breaker/backoff on a persistently-failing host yet — a feed that fails every tick retries every tick until its cron slot passes; acceptable given the 60s poll is cheap (cron-gated, not per-list every tick) |
| D2 | Scheduled refresh | **DoS** — the fix in this PR made the scheduled path call the real importer for the first time; a bug here could make every due list re-import on every 60s tick instead of once per cron slot | `isDueForScheduledRefresh()` compares `lastUpdatedAt` against the current minute — a list is only due once per matching cron slot; covered by unit tests (`SanctionsListServiceTest`) | Verify in a live sandbox before relying on it — no integration/Testcontainers run against a real external network signal was possible in this sandboxed session (see PR notes) |
| E1 | Roles | **Elevation** — a viewer/service role obtains operator-only mutation (registry update, manual refresh) | Distinct `@RolesAllowed` tiers per endpoint; `listAll`/`getById` allow `ROLE_SERVICE` (read-only, for KYC/payment callers), `update`/`refresh`/`refreshAll` require `ROLE_OPERATOR`/`ROLE_ADMIN` | OPA enforce still advisory fleet-wide — *open*, same as fraud-service E1 |
| S2 | OIDC client secret | **Spoofing (shared-credential blast radius)** — reuses the shared `openbank-services` Keycloak confidential client, same pattern as the rest of the fleet | Secret Vault-projected; confidential client; role-gated endpoints | Shared-credential blast radius accepted for sandbox only; dedicated per-service Vault path is prod hardening — *open* |

## 4. Key invariants (must never regress)

- **Every registered `SanctionsList.sourceUrl` is a real, authoritative, publicly-documented feed**
  (OFAC Treasury XML, OpenSanctions `targets.simple.csv` bulk exports, or the CNB migration-seeded
  exception) — never a placeholder or invented URL. Changing a `source_url` is an operator action
  gated by `ROLE_OPERATOR`/`ROLE_ADMIN`.
- **The scheduled refresh path must actually invoke the real importer** for every due, enabled
  list — a stub or no-op here silently blinds the fleet's screening data with no error surfaced
  (this was the actual gap this PR fixes; see change log).
- **A failure importing one list must not block any other list's refresh** in the same scheduled
  tick (`scheduledRefresh()` catches per-list and logs, then continues).
- Import parsers **never buffer an entire feed body in memory** — SAX streaming (OFAC) / line
  buffered reading (OpenSanctions CSV) only, so a large feed (the PEP dataset is ~190 MB) cannot
  exhaust heap.
- The **domain layer is framework-free** (ADR-0002); `SanctionsEntry`/`SanctionsList` have zero
  Quarkus/Panache imports.
- CNB domestic entries have **no machine-readable feed** — they are Flyway-seeded (V6) as a
  documented, intentional exception, not an oversight.

## 5. Open items / follow-ups

- **No source-host allowlist (T1).** An authorized operator can repoint any list's `source_url` to
  an arbitrary host today; nothing in code restricts it to `treasury.gov`/`opensanctions.org`.
  Recommended before prod: validate `source_url` host against a per-`listType` allowlist in
  `SanctionsListService.update()`.
- **No content-integrity check beyond TLS (T2).** OFAC's response includes a `Digest` header
  (`sha-256=...`) that is not currently verified.
- **No circuit breaker on a persistently-failing feed (D1).** Acceptable today given the cron-gated
  poll cadence; revisit if a feed host starts rate-limiting or blocking the service's `User-Agent`.
- **Signed/immutable import audit trail (R1)** — same ADR-0029 evidence-bundle gap as fraud-service.
- **OPA enforce (S1/E1)** — authz is advisory fleet-wide; enforce before the money-path go-live
  gate closes.
- **Dedicated OIDC credential (S2)** — shared confidential client accepted for sandbox only.
- **KYC vendor risk-check integration is explicitly out of scope of this service and this PR.**
  `openbank-kyc-service` has no sanctions/entry model of its own — it is case management only and
  consumes party events; it legitimately has no external watchlist feed integration yet (ADR-0116
  §"External watchlist... Planned"). This threat model and PR close the **sanctions-screening feed
  registration/wiring** half of ADR-0116's screening surface, not the KYC-side vendor integration.

## 6. Manual review of a screening hit (`sanctions.clear`)

Sections 1-5 model the **import** side only — feeds, `source_url` integrity, parser resource use.
They omit the disposition side entirely, which is the higher-consequence half:
`rules.yaml: money_path_services` justifies this service's entry on precisely that action, since
"a wrongly-cleared true positive is a real sanctions violation". This section closes that gap.

**Asset.** The disposition decision itself — the transition of a `HIT` / `POTENTIAL_HIT` to
`CLEAR` / `WHITELISTED` / `ESCALATED`, together with `reviewed_by` and `review_note`. A wrongly
recorded `CLEAR` releases a payment that a real match should have stopped; a lost or unattributable
decision destroys the audit trail that a screening call was ever adjudicated by a human.

**Entry point.** `POST /api/v1/sanctions/review` — `@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")`,
`@Authorize(action = "sanctions.clear")`, deliberately no `ROLE_API`; there is no M2M caller (all
six `SanctionsServiceClient` interfaces declare only `/screen`). Externally routable via the
`sanctions-api` Ingress (`api.open-bank.tech`, prefix `/api/v1/sanctions`).

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| C1 | **Elevation / segregation of duties** — one operator both raises and clears a hit | Four-eyes on `sanctions.clear` (ADR-0155): `AuthorizeInterceptor` pauses the maker's call with 202 + a `PendingApproval` id; a DIFFERENT principal decides it via `PATCH /api/v1/sanctions/approvals/{id}`; the maker retries with `X-Approval-Id`. Enforced centrally in `RedisApprovalStore.decide`, not per-service, so a service's REST layer cannot forget it | **The guard compares principal-id strings.** One human or agent holding two identities satisfies it — #3190 is the measured lending instance. Mechanism-level limit, not an implementation defect; mitigating it needs identity-level controls, not more code here. **Separately, the guard is unfalsified (#3349):** no test anywhere feeds it a maker approving themselves — `AuthorizeInterceptorTest`'s fake re-implements the check, so deleting the production line stays green |
| C2 | **Over-broad approval grant** — a checker approves one specific decision, the maker applies it elsewhere | `AuthorizeInterceptor.satisfies()` binds an approval to (action, resourceId, maker), and `markExecuted` makes it one-time — an EXECUTED approval can never be replayed | `@Authorize(action = "sanctions.clear", resource = "")` carries an **empty resource**, so a granted approval authorises *any* review by that maker, not the check the checker actually looked at. Narrowing it to `resource = "#cmd.checkId"` is the fix; deferred because no UI drives this flow yet (#3334) |
| C3 | **Repudiation** — a cleared hit with no attributable decider | `reviewed_by` / `review_note` / `reviewed_at` persisted on the check; a `SanctionChecked` outbox event is emitted in the **same transaction** as the update, so audit/AML consumers cannot miss a decision that was durably recorded | Outbox delivery is at-least-once, not exactly-once; consumers must dedupe. `OutboxMessage.createdAt` defaults to `Instant.EPOCH` fleet-wide (#3272), which inverts FIFO claim order |
| C4 | **Tampering via lost update** — a review silently overwrites a concurrent one | Single-statement `merge` inside one transaction | **No optimistic locking**: the entity has no `@Version`, so two concurrent reviewers last-write-wins with no conflict surfaced. Low likelihood while the queue is worked by hand; revisit if #3334 makes review routine |
| C5 | **Denial of the control itself** — the review path does not work at all | Covered by `SanctionsReviewUpdateIT` against a real Postgres | Was live until this change: `review()` called the insert path on an application-assigned `@Id`, so **every** review died at flush on `sanctions_checks_pkey` (ADR-0126 D3). Nothing alerted — see below |

**Invariant (must never regress).** The review path performs an **UPDATE**. The insert path
(`saveWithEvent`) and the update path (`updateWithEvent`) stay separate: `merge` cannot raise a
primary-key violation, and the insert path deliberately lets that violation escape (#3264), because
there it signals a real defect rather than an idempotent replay. Collapsing the two into an
unconditional upsert would delete that guard and render `SanctionsIdempotentReplayIT`'s
primary-key case structurally unable to fail.

**Detection gap worth naming.** C5 was invisible to every layer: the service's unit test stubbed the
repository port, so it asserted a call that in production always threw; no UI exercised the endpoint
(#3334); no M2M caller existed; and a failed review is a 500 on a rarely-used path, which no alert
distinguishes from noise. The only signal available anywhere was `listPending()` never shrinking —
and nothing alerts on a queue that fails to drain (the same class as #3273).

## 7. Change log

- **2026-07-07** — Verified the sanctions/PEP feed registry (`sanctions_lists`, Flyway V3/V7/V8) was
  already populated with real, live source URLs (OFAC Treasury `sdn.xml`; EU/UN/HM Treasury via
  OpenSanctions `targets.simple.csv` bulk exports) — an earlier audit's claim that screening ran
  against "in-memory/seed lists only" was stale. The actual gap found and fixed: **`SanctionsListService.scheduledRefresh()`
  never invoked the real importer** — the `@Scheduled(every = "60s")` job's Mutiny `Uni` pipeline
  fed a hardcoded `0` into the import branch (`// importer is suspend — invoke via separate
  coroutine in prod`), so no scheduled tick ever actually fetched or upserted anything regardless
  of what was registered in `sanctions_lists`. Rewrote it as a plain `suspend fun` (Quarkus's
  scheduler has native Kotlin-coroutine support, same pattern as the fleet's `AbstractOutboxDispatcher`
  subclasses) that calls the existing, already-tested `refresh()` per due list, with per-list
  failure isolation. No new HTTP endpoint; no change to any REST contract; no DB schema change.
  Rollback: revert `SanctionsListService.scheduledRefresh()` to the prior stub (loses the fix, not
  a regression risk — the old code shipped no scheduled imports either way).
