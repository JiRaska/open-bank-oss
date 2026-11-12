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
- **Last reviewed:** 2026-10-29
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
5. **The monitored-subject registry and its findings** (ADR-0376, section 6b) — personal data of
   the subjects other services asked this one to keep screening, and the fact that one matched.

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
| D1 | Scheduled refresh | **DoS** — a slow source or large feed exhausts time or resources | CSV records are processed in batches of 500; XML imports retain parsed entries and reconciliation retains source IDs. Publisher pages are bounded independently of feed size. Per-list exception handling isolates failed attempts. | A hung stream can still delay the sequential refresh loop; connection/request timeouts do not establish an independent streaming-body deadline. Importer memory and PostgreSQL temporary storage require capacity planning. |
| D2 | Scheduled refresh | **DoS** — the fix in this PR made the scheduled path call the real importer for the first time; a bug here could make every due list re-import on every 60s tick instead of once per cron slot | `isDueForScheduledRefresh()` compares `lastUpdatedAt` against the current minute — a list is only due once per matching cron slot; covered by unit tests (`SanctionsListServiceTest`) | Verify in a live sandbox before relying on it — no integration/Testcontainers run against a real external network signal was possible in this sandboxed session (see PR notes) |
| E1 | Roles | **Elevation** — a viewer/service role obtains operator-only mutation (registry update, manual refresh) | Distinct `@RolesAllowed` tiers per endpoint; `listAll`/`getById` allow `ROLE_SERVICE` (read-only, for KYC/payment callers), `update`/`refresh`/`refreshAll` require `ROLE_OPERATOR`/`ROLE_ADMIN` | OPA enforce still advisory fleet-wide — *open*, same as fraud-service E1 |
| S2 | OIDC client secret | **Spoofing (shared-credential blast radius)** — reuses the shared `openbank-services` Keycloak confidential client, same pattern as the rest of the fleet | Secret Vault-projected; confidential client; role-gated endpoints | Shared-credential blast radius accepted for sandbox only; dedicated per-service Vault path is prod hardening — *open* |
| T4 | Committed list changes | **Silent loss** — entry batches commit but the process fails before publishing their changes | V15 appends journal evidence in the same PostgreSQL transaction as each entry mutation, including old-version writers during rollout. The publisher locks an exact selection and writes outbox rows plus journal deletion in one repeatable-read Panache transaction. Failure rolls back the publication; the scheduled loop retries retained evidence outside the feed cron too. | Broker delivery remains at-least-once; consumers must deduplicate ce-id. The KYC re-screen consumer is a separate integration; an outbox row does not prove a completed re-screen. |
| T5 | Withheld list changes | **DoS / missed re-screening** — a bulk source reformat or unaddressable identifier causes excessive or incomplete targeted work | Actual population and changed targets are summarized in SQL before paging. A share above the configured threshold, missing source ID, or oversized ID withholds targeted events and retains the journal. A distinct count-only signal is deduplicated across identical retries. Normal events contain at most 64 bounded identifiers; transaction-local SQL tables and flush/clear between pages bound publisher heap. | Retained evidence and SQL temporary storage grow with pending changes. Operator handling and KYC re-screening remain separate integrations; the count event alone is not an operational resolution. A legitimate bulk update can require deliberate intervention. |
| T6 | Concurrent list refresh | **DoS** — another pod publishes an early committed batch before the rest of a large refresh arrives, letting each fragment pass the storm threshold | A per-list PostgreSQL advisory lock serializes refreshes. A durable active generation is checked inside every independent batch, metadata update, and publication transaction. Publishers defer while the generation is active, even if the lock owner loses its database connection; a successor advances the generation and fences the stale writer. | A failed import can leave committed partial changes; their journal remains fenced and publication stays deferred until a subsequent complete refresh. Repeated source failure leaves the evidence pending for operator review. A stalled source stream can hold the lock and queue same-list refreshes while publishers defer (D1). |
| S3 | mTLS listener (8443) | **Spoofing / Elevation** — a second inbound listener (private-CA TLS, client certificate REQUIRED, TLSv1.3) beside the plain port; a workload holding any fleet-CA certificate and no token could be treated as an authenticated caller, or a caller without a certificate could reach the API over it | `quarkus.http.ssl.client-auth: required` is baked into the `%prod` image, so the handshake refuses a client with no trusted certificate; `/api/*` accepts the bearer mechanism ONLY on both listeners, so the certificate is transport authentication and the OIDC bearer stays the identity `@RolesAllowed` and OPA decide on. The NetworkPolicy admits the same declared caller namespaces on 8443 as on 8123. A listener-parity integration test in this module drives both ports with the production listener shape (no certificate: handshake refused; certificate without bearer: 401; bearer: served identically); it is named in the notes below and is cited in this row only once scheduled evidence on main shows it green. Gates `mtls-bearer-only`, `mtls-keeps-http-port` | The plain port (8123) stays open for the existing callers until each is migrated, so the listener adds a confidential path and removes none. Any fleet-CA certificate passes the handshake (no per-caller certificate allow-list); caller identity rests on the bearer. |

## 4. Key invariants (must never regress)

- **Every registered `SanctionsList.sourceUrl` is a real, authoritative, publicly-documented feed**
  (OFAC Treasury XML, OpenSanctions `targets.simple.csv` bulk exports, or official e-Sbírka SPARQL
  open data) — never a placeholder or invented URL. Changing a `source_url` is an operator action
  gated by `ROLE_OPERATOR`/`ROLE_ADMIN`.
- **The scheduled refresh path must actually invoke the real importer** for every due, enabled
  list — a stub or no-op here silently blinds the fleet's screening data with no error surfaced
  (this was the actual gap this PR fixes; see change log).
- **A failure importing one list must not block any other list's refresh** in the same scheduled
  tick (`scheduledRefresh()` catches per-list and logs, then continues).
- Import parsers **never buffer an entire feed body in memory** — SAX streaming (OFAC) / line
  buffered reading (OpenSanctions CSV) only, so a large feed (the PEP dataset is ~190 MB) cannot
  exhaust heap.
- **A client certificate on the 8443 listener is never an identity.** The API is authenticated by
  the OIDC bearer alone, identically on 8443 and on the plain port (`SanctionsListenerAuthParityIT`);
  removing the bearer-only permission while `client-auth` stays on turns every fleet-CA
  certificate into a login.
- The **domain layer is framework-free** (ADR-0002); `SanctionsEntry`/`SanctionsList` have zero
  Quarkus/Panache imports.
- The Czech national list (`CNB_DOMESTIC`, kept by MZV under Act No. 1/2023 Coll.) is imported
  from the OpenSanctions `cz_national_sanctions` mirror like the other CSV feeds (#10757); the V6
  demo rows are retired by the first import's reconciliation sweep.
- `CZ_TERRORISTS` is a separate legal regime under Regulation No. 210/2008 Coll. Its only source
  is official e-Sbírka SPARQL open data, not the commercially licensed OpenSanctions mirror. The
  parser validates every designated fragment before the first upsert; a malformed or duplicate
  target resolves to `FAILED_KEPT_EXISTING` and cannot trigger reconciliation deletion.
- High-risk screening events may fan out to `openbank.oversight-triggers` only through the durable
  outbox and only as a minimal `sourceService` + `occurredAt` wake-up. Screened names, identifiers,
  match details and list data must never be copied to that topic; original ce-id/ce-type headers
  retain correlation and retry identity (ADR-0031 D9, #13179).

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
| C2 | **Over-broad approval grant** — a checker approves one specific decision, the maker applies it elsewhere | `AuthorizeInterceptor.satisfies()` binds an approval to (action, resourceId, maker), and `markExecuted` makes it one-time — an EXECUTED approval can never be replayed | **RESOLVED.** `@Authorize(action = "sanctions.clear", resource = "#cmd.checkId")` binds the approval to the specific check (ADR-0206 dotted-path extraction), so a checker approving "clear check A" no longer authorises "clear check B". Previously the empty resource made a granted approval a bearer token for any review by that maker, replayable by sending the same `X-Approval-Id` against a different `checkId`. Residual: the extraction fails CLOSED — an unknown field name yields no resource and silently restores the unscoped behaviour, so `SanctionsClearResourceScopeTest` asserts the field resolves, not merely that the string is present |
| C3 | **Repudiation** — a cleared hit with no attributable decider | `reviewed_by` / `review_note` / `reviewed_at` persisted on the check; a `SanctionReviewed` outbox event is emitted in the **same transaction** as the update, so audit/AML consumers cannot miss a decision that was durably recorded — and since #1035 it is a *distinct* event type from the automated `SanctionChecked`, so a consumer can attribute the decision to a human without inferring it from a nullable `reviewedAt` | Outbox delivery is at-least-once, not exactly-once; consumers must dedupe. `OutboxMessage.createdAt` defaults to `Instant.EPOCH` fleet-wide (#3272), which inverts FIFO claim order |
| C4 | **Tampering via lost update** — a review silently overwrites a concurrent one | Single-statement `merge` inside one transaction | **No optimistic locking**: the entity has no `@Version`, so two concurrent reviewers last-write-wins with no conflict surfaced. Low likelihood while the queue is worked by hand; revisit if #3334 makes review routine |
| C5 | **Denial of the control itself** — the review path does not work at all | Covered by `SanctionsReviewUpdateIT` against a real Postgres | Was live until this change: `review()` called the insert path on an application-assigned `@Id`, so **every** review died at flush on `sanctions_checks_pkey` (ADR-0126 D3). Nothing alerted — see below |
| C6 | **Information disclosure via the checker queue** — `GET /api/v1/sanctions/approvals` lists every pending four-eyes request with its `makerId` and age | Role-gated `ROLE_OPERATOR`/`ROLE_ADMIN` + `@Authorize(action = "sanctions.approval.read")`; the payload carries approval metadata only — the action name, the resource id and who asked — never the screened subject, their identifiers or the match detail, all of which stay behind `sanctions.read` | The queue is deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth. `limit` is clamped to 200 — an unbounded query parameter over a Redis scan is a trivially reachable amplification |
| C7 | **Clearance replay / over-broad whitelist** — a stale `CLEAR`, another subject's review, or an old whitelist permits a newly sanctioned party | Every new idempotency key performs a fresh screen before clearance is considered. `WHITELISTED` lasts 90 days and requires exact equality of `subjectId`, entity type, name, aliases, birth date, nationality and identifiers. It covers only reviewed `(listType, externalId)` identities, falling back to `(listType, matchedName)` only when external id is absent. New uncovered hits and expired or changed-subject clearances stay blocking. Legacy rows without `subjectId` cannot newly become `WHITELISTED`; they require a fresh screen and review | The 90-day validity is a compliance risk decision, not proof that a party remains low risk. Exact attributes mean a legitimate party-data correction requires a new review. Reviews still have the C4 last-write-wins limitation |

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

## 6b. Ongoing monitoring of registered subjects (ADR-0376)

Off by default (`openbank.sanctions.monitoring.enabled=false`). This section describes the
surface that exists once an environment switches it on.

**New asset: the monitored-subject registry.** `sanctions_monitored_subjects` (V23) is the first
store in this service that holds a standing population of the bank's own customers rather than
one row per screening request: name, aliases, date of birth, nationality, country and entity
type per subject, keyed by the owning service and its reference.
`sanctions_monitored_subject_names` repeats each subject's normalised names, one row per name,
for the index the delta match reads; it is deleted with the subject. `sanctions_monitoring_findings` links
a subject to the list entries it matched, which is the more sensitive fact: that a named
customer is, or resembles, a sanctioned or politically exposed person.

**New inbound operations.** `PUT` and `DELETE /api/v3/sanctions/monitored-subjects/{subjectRef}`
(`SanctionsMonitoringResource`), for `ROLE_API` with the `sanctions.create` action of
`POST /api/v3/sanctions/screen`. The owner of a registration is not a request value: it is the
client the caller's service-account token was issued to (`MonitoringCallerIdentity`). There is no
read operation.

**New internal flows.** A trigger on `sanctions_change_journal` queues each committed list-entry
change (only while the registry is non-empty); a scheduled delta matches queued entries against
the registry; a scheduled sweep re-evaluates whole subjects; a finding and its outbox event are
written in one transaction and leave on `openbank.sanctions.screening.event` as
`MONITORED_SUBJECT_MATCHED`.

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| M1 | **Tipping-off (information disclosure)** — a customer, or a service facing one, learns that the customer matched a list | A finding is never returned over REST: the resource has no read operation, and a registration response carries the registration only. An integration test in this module registers over real HTTP and asserts the exact set of response fields; it is cited in this row only once scheduled evidence on main shows it green. The event names the subject by reference and goes to the internal screening topic, which no customer-facing service consumes | The topic is shared: `audit-service` archives it, so the pair (subject reference, matched entry) is also stored there. Nothing enforces that a future consumer keeps it off a customer surface; ADR-0371 names a gate for that class of event, which does not cover this one yet |
| M2 | **Personal data at rest** — the registry concentrates customer identity data in a service that did not hold a customer population before | Minimum fields: exactly what the CUSTOMER screening profile reads, no document identifiers, no contact data. Stored under the service's existing `restricted` classification and database encryption | Names and dates of birth are stored in clear in the column, as `sanctions_checks` already does; no field-level encryption (ADR-0189 position) |
| M3 | **Erasure not honoured** — a subject is offboarded or erased and stays monitored | Deregistration issues a `DELETE`; the subject's indexed names and its findings go with it by `ON DELETE CASCADE`. It is idempotent and is not gated on the feature flag, so erasure works where monitoring is switched off. An integration test in this module deletes a registered subject with the flag off; it is cited in this row only once scheduled evidence on main shows it green | Events already published remain in the outbox until its retention purge and in the audit archive under its own retention. Deregistration is the caller's duty: nothing here learns of an erasure by itself |
| M4 | **Monitoring evasion by deregistration (tampering)** — a caller withdraws a subject so that a later listing is never matched, or replaces its attributes with ones that match nothing | The owner is resolved server-side from the verified service-account token (`MonitoringCallerIdentity`, on `ServiceAccountIdentity`: a verified JWT whose `azp` is bound to that client's service-account username) and is part of the key of every read, replace and delete, so a caller reaches only the subjects it registered; the same reference under another owner is a different row. A person's token, a principal that is not a verified service-account token, and the fleet's shared client are refused with 403 before anything is read. Integration tests in this module plant another owner's subject under the caller's own reference and assert it survives the caller's delete, and cover each refusal; they are cited in this row only once scheduled evidence on main shows them green | A service can still withdraw or overwrite its OWN subjects: a compromised or defective owner evades monitoring of its own customers. Each deregistration is logged with the owning client (no name), but nothing alerts on deregistration volume. The binding is as strong as the realm's client separation: where two services are issued the same client id they are one owner. An environment whose callers still authenticate through the shared client cannot register at all |
| M5 | **Names in logs** — the registry's content leaks through log lines | Monitoring code logs the registry row id, the owning service and list-entry references only, never a subject's name or reference | Convention, checked by review; no gate inspects log arguments |
| M6 | **A missed delta (silent false negative)** — a list change is never matched against the registry | The queue row commits in the transaction of the list change itself. A delta too large to match entry by entry is replaced, in one transaction, by a request to re-sweep every subject. The periodic sweep re-evaluates every subject through the live screen's own evaluation whatever the deltas did | Bounded by the sweep interval (default 30 days) for anything only the sweep can see, which includes jurisdiction reference data: a newly listed country reaches a registered subject at its next sweep, not immediately |
| M7 | **Duplicate or replayed findings (DoS of the review queue)** | A finding is unique per subject, list entry and version of the entry's names (partial unique index); the version ignores alias order, so a feed that reorders names raises nothing | An entry whose names genuinely change raises a new finding for a subject already found against it |
| M8 | **A clearance silences a true match** | Monitoring calls the live screen's own clearance rule: same subject reference, same attributes, unexpired, covering the exact entry identity. PEP and jurisdiction matches are never covered. An integration test in this module records a clearance and asserts which later findings it does and does not silence; it is cited in this row only once scheduled evidence on main shows it green | The registry holds no document identifiers, so a clearance recorded on a screen that carried identifiers is not honoured here and the finding is raised: the safe direction |
| M9 | **Resource exhaustion** — the delta matches every changed entry against the registry and shares the database with payment screening | The match is index-driven: each entry probes the partial trigram indexes of `sanctions_monitored_subject_names` and scores only the candidates (`MonitoringNameIndex`), measured at 0.3 s per entry on one million synthetic subjects against 7.3 s for a full scan. Both paths are bounded per tick (entries per run, subjects per batch), one entry may raise at most a bounded number of findings, and the delta falls back to the sweep above a backlog bound | The cost still grows with the registry and with how common an entry's trigrams are: 5,000 changed entries a day are about 25 minutes of one database core at one million subjects. The figures come from synthetic names; no measurement on production-shaped data exists. A caller can register subjects without a quota, so one owner can grow the registry for every other |
| M10 | **A disabled feature read as a working one** | Registration is refused with 503 while disabled; a disabled run reports `DISABLED`, counted apart from `completed`; the registry gauges read NaN until a run has observed the registry | None known |

## 7. Change log

- **2026-11-12** — **Ongoing monitoring of registered subjects (ADR-0376), shipped disabled.**
  New store of personal data (`sanctions_monitored_subjects`, `sanctions_monitoring_findings`,
  `sanctions_monitored_subject_names`, V23), two new inbound operations (`PUT`/`DELETE
  /api/v3/sanctions/monitored-subjects/{subjectRef}`, owner taken from the caller's
  service-account token) and one new outbound event (`MONITORED_SUBJECT_MATCHED`). See section 6b for the threats: tipping-off (M1), the registry
  as a personal-data store (M2), erasure (M3), evasion by deregistration (M4), missed deltas (M6)
  and clearances (M8). With the flag off, which is the default, registration answers 503, no run
  matches anything and the queue trigger writes nothing because the registry is empty.

- **2026-11-11** — **Jurisdiction reference data loaded, its ageing made observable, and a
  review band for customer names (#15723).** V22 loads the EU list of high-risk third countries
  (Commission Delegated Regulation (EU) 2016/1675, Annex, consolidated version of 2026-01-29;
  26 countries, all `HIGH_RISK`), so a screen's `country` / BIC country can now match. No row is
  `EMBARGO` and the FATF lists are not loaded: their publisher could not be retrieved and no row
  is written from memory. **STRIDE-T / data integrity:** the rows are reference data copied by
  hand from a publication, and the failure mode is silent — a newly listed country keeps
  screening CLEAR. Each row therefore carries `published_on` and `verified_on`, and
  `JurisdictionReferenceDataGauge` publishes their age per `source_list` from the stored dates
  (correct on a cold pod; a restart cannot reset it). `JurisdictionReferenceDataNotReverified`
  fires past 150 days without a comparison against the source, `…NotLoaded` reports an expected
  list with no rows, `…Unknown` an unreadable or missing row count. Still no write endpoint:
  rows change only by reviewed migration. **Name matching:** the candidate search used the HIT
  threshold (0.85) as its floor for every caller, so `POTENTIAL_HIT` was unreachable from a name
  and a near-miss spelling of a listed person screened CLEAR. `profile=CUSTOMER` now searches
  down to 0.65 and returns such a name as `POTENTIAL_HIT` (PEP entries: `pepStatus`).
  **Clearances (STRIDE-E):** an analyst's `WHITELISTED` decision used to cover every match
  identity on the check, and a jurisdiction match was identified by list and country only — so
  a clearance recorded while a country was `HIGH_RISK` went on to whitelist the same country
  after a superseding `EMBARGO`. A clearance dismisses a name false positive; a jurisdiction
  match is a fact about the country. No clearance covers one now: name matches on the check may
  be cleared, the status is then the jurisdiction match's own, never `WHITELISTED`
  (`JurisdictionClearanceIT`), and `matchedId` carries source list, measure and version.
  **Residual:** `profile=PAYMENT` and profile-less screens keep the 0.85 floor on purpose —
  the payment admission chain has no held state to park a review in (#15724) — so a near-miss
  spelling still clears a payment. A country listed only by a FATF statement and not by the EU
  regulation is not matched. A `HIGH_RISK` row sends every screen naming that country to review;
  no rail passes `country` yet, so this is latent until a caller opts in. Evidence:
  `JurisdictionReferenceDataIT`, `SanctionsFuzzyNameBandIT` (real Postgres, must-hit and
  must-clear), `sanctions_jurisdiction_alerts_test.yaml` (promtool).
- **2026-11-11** — **Screening profiles, country and bank identifier on `/api/v3/sanctions/screen`
  (#15723).** Three optional inputs; a request without them is unchanged, and `/api/v1` rejects
  them (400). `profile=PAYMENT` consults the sanctions lists, the national terrorist list and the
  jurisdiction data and no PEP list; `profile=CUSTOMER` adds the PEP list and reports it apart
  (`matchKind: PEP`, `pepStatus`), never in `status`. A listed `country`, or the country inside a
  BIC, matches independently of the name (`EMBARGO` = HIT, `HIGH_RISK` = POTENTIAL_HIT at 0.65).
  **STRIDE-T:** `profile` and `listTypes` together are refused, so a caller cannot name a profile
  and silently narrow it; an unknown profile, country or BIC shape is a 400, never a narrower
  screen. The jurisdiction table has no write endpoint: rows arrive only by reviewed migration,
  so a single operator cannot delist an embargoed country at run time. **STRIDE-E:** no new
  action or role; the operation keeps `sanctions.create`. **Residual:** the jurisdiction table
  ships EMPTY and has no feed — until compliance adds rows a country never matches, so this
  change alone embargoes nothing. Existing rails still call `/api/v1` without a profile and keep
  the old list set (PEP counted, national terrorist list absent) until each opts in. A BIC is
  checked for its country only; listed banks are not matched by identifier. A WHITELISTED
  clearance now also requires the same profile, country and BIC. Evidence:
  `SanctionsScreeningProfileIT` (real Postgres, must-hit and must-clear per profile). Rollback:
  revert the PR; V21 only adds nullable columns and a new table.
- **2026-11-04** — **Deferred single-list refresh trigger, v2 (#14780).** New `POST
  /api/v2/sanctions/lists/{listType}/refresh` flags the list (`refresh_requested_at`, the #9048
  refresh-all mechanism) and answers 202 without downloading anything. The import runs through the
  fenced `refresh()` on the next scheduler tick, so publication fencing and the change journal are
  unchanged. It requires `Idempotency-Key`, uses the same OPA action `sanctions.trigger` and the
  same roles (`ROLE_OPERATOR`, `ROLE_ADMIN`) as v1, so no new privilege exists. The v1 operation
  is UNCHANGED (synchronous import, 200 + the refreshed list). **STRIDE-D:** a caller can now
  trigger a refresh without holding a request open for a whole feed download (API fuzz timed out
  on PEP_GLOBAL via v1). **STRIDE-R:** v2 refuses a DISABLED list (422) rather than accepting work
  the scheduler would never carry out. **Residual:** v1 still holds the request for the full
  import; the admin console moves to v2. Evidence: `SanctionsListRefreshDeferredIT`. Rollback:
  revert the PR; no migration.
- **2026-11-04** — **v1 single-list refresh cannot stack concurrent imports (#14780).** **STRIDE-D:**
  `POST /api/v1/sanctions/lists/{listType}/refresh` still imports in-request (contract unchanged),
  and before this a second call for the same list waited on the fence's advisory lock (up to the 30 s
  `lock_timeout`, holding a request thread), then ran another full import, so repeated calls on a
  large feed (PEP_GLOBAL, ~700k entries) queued one full download each. The HTTP path now takes
  the SAME advisory key with `pg_try_advisory_lock` and refuses at once with **409 + Retry-After**
  (`SanctionsRefreshInProgress`, rendered by libs-runtime's `DomainExceptionMapper`) when the list is
  already refreshing, whether the other refresh is a request or the scheduler. The scheduler keeps
  its bounded wait. 409 is documented in `openapi.yaml` as an additive response. Evidence:
  `SanctionsListRefreshDeferredIT` drives two concurrent v1 calls against a held import. The
  second gets 409, and with the old waiting lock it hung until the client's read timeout. Rollback:
  revert the PR.

- **2026-10-29** — **Subject-bound, expiring whitelist retry (#11694).** A new screening attempt
  no longer needs to inherit an old `CLEAR`: after the fresh list search, an unexpired
  `WHITELISTED` review may cover exactly the same explicit subject and reviewed match identities
  for 90 days. New uncovered hits, changed subject attributes and expired decisions remain
  blocking. `subject_id`, `clearance_expires_at` and their lookup index are additive in Flyway V17;
  legacy rows have no subject and cannot newly be whitelisted, so their transition is a fresh
  screen plus review. Covered by `SanctionsClearanceTest` and real-Postgres
  `SanctionsReviewUpdateIT`. Rollback: stop clearance lookups and roll back both account and
  sanctions writers/readers before dropping V17's index and columns as documented in the migration.
- **2026-10-24** — **Check status and block threshold shared with the rails (ADR-0353, #13439).**
  `SanctionsCheckStatus` is now a typealias of `openbank-libs-fincrime`'s `ScreeningMatchStatus`,
  and `isHighRisk` reads the same `POTENTIAL_HIT_BLOCK_THRESHOLD` (0.85) the rails' `ScreeningPolicy`
  blocks on, so the two can no longer drift. Persisted values (`@Enumerated(STRING)`) and the wire
  spelling are unchanged. Rollback: revert the PR; no data or contract change.
- **2026-10-03** — **SENT outbox rows are purged after 7 days** (ADR-0329, ADR-0327 D8). `sanctions_outbox` kept every SENT row, payload included, indefinitely: `purgeSent` existed and nothing called it. The shared libs-runtime `OutboxSentRetentionJob` now deletes SENT rows whose `sent_at` is older than `openbank.outbox.retention.sent-days` (default 7) nightly in bounded batches; its v1 repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`. PENDING, FAILED, DISPATCHING and DEAD rows are never touched. Information disclosure: shrinks the window in which a database read (replica, backup, operator query) exposes past event payloads. No new endpoint, caller or privilege; replaying an event older than 7 days now comes from the broker or audit-service, not this table.

- **2026-10-08** — **OPA authorization ENFORCED (`AUTHZ_ENFORCE=true`, #12304).** Every `@Authorize` method (screen, hit review, watchlist edit, list refresh, four-eyes approval) now blocks on a deny (403) and fails closed when the PDP is unreachable (503). Before this the decisions were advisory and only counted, so `@RolesAllowed` was the only gate. Checked with `opa eval` against the regenerated `sanctions-opa-bundle` ConfigMap. The six M2M screening callers (account, domestic-payment, sepa-payment, sepa-instant, fx, kyc) call only `POST /screen` = `sanctions.create`, and every `service-account-*` principal is allowed it via `m2m-sanctions-screening` (with that rule disabled, those rows turn to DENY). Operators and admins keep the whole family. The agent service account keeps `sanctions.list`/`sanctions.read`. A `ROLE_API` service account is denied clear/update/trigger, and anonymous, customer and AI-agent principals are denied every write. One grant was added so enforcing does not remove access the compliance personas use: `compliance-sanctions-list` lets a human (never a `service-account-*`) with `ROLE_COMPLIANCE` list checks, hits and pending (base `rest.rego` grants that role only `*.read`). Residual: a service account holding `ROLE_OPERATOR` (`service-account-openbank-edge` in the deployed realm, the shared client in the CI realm) is classified `HUMAN` and is allowed every sanctions write by `operator-sanctions-write`, including `sanctions.clear`, which is still four-eyes gated. A `ROLE_VIEWER`-only human is denied list/read. Rollback: set `AUTHZ_ENFORCE` back to `"false"` and restore the allowlist entry in `check-authz-enforce-money-path.py`.
- **2026-09-26** — **AuthzProducer replaced by the shared libs-runtime OPA PDP producer** (PR
  #10952). The service-local a per-service AuthzProducer (since consolidated into libs-runtime `OpaPolicyDecisionPointProducer`) is deleted;
  `application.yaml` now sets `openbank.authz.opa-pdp-producer.enabled: true` to opt into
  `OpaPolicyDecisionPointProducer` (openbank-libs-runtime), gated by that build
  property (`enableIfMissing = false`), so the wiring stays off for any service that does not set
  it. The producer is NOT `@DefaultBean`: if this service's own `src/main` ever produces a second
  `PolicyDecisionPoint` bean, `quarkusBuild` fails loudly on an ambiguous CDI dependency instead of
  one silently displacing the other. Same `opa.url`/`opa.path`/`opa.timeout-ms` defaults as the deleted producer
  (`http://localhost:8181`, `/v1/data/openbank/rest/allow`, 500 ms) and the same fail-closed
  behaviour on OPA sidecar failure — `OpaSidecarPolicyDecisionPoint` itself is unchanged, only its
  construction site moved from a per-service copy to the shared producer. No new caller, endpoint,
  network edge or privilege; no new trust boundary.

- **2026-09-13** — Add durable list-change publication (ADR-0256 D1). Flyway V15 installs an
  append-only journal trigger; publication atomically transfers an exact committed selection to
  the existing outbox. When an invalid source identity is repaired or its entry is removed,
  publication marks only its unpublishable historical rows as resolved while retaining them for
  audit. A repair records the target list/key across lists; a removal records its reason. Resolution
  shares the outbox transaction: a failed write restores the rows for retry. It is not evidence that a
  downstream re-screen completed. Retry survives failed imports and process interruption.
  Equivalent alias/program/nationality ordering does not trigger a new change; malformed legacy
  text remains repairable. SQL working sets and bounded pages prevent fleet-sized journals from
  becoming fleet-sized JVM allocations. Change messages are additive chunks on the existing
  topic; withheld messages retain evidence and do not claim a downstream re-screen completed.
  Migration rollback requires stopping publication and preserving pending journal evidence before
  dropping the trigger/tables. Existing outbox rows remain dispatchable. The KYC consumer and
  operator resolution workflow are separate delivery requirements.

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

- **2026-08-02** — **New inbound REST surface: `GET /api/v1/sanctions/approvals`** (issue #3472), the
  checker's queue. Until now `ApprovalResource` served only `PATCH /{id}`, so a decision parked at 202
  was discoverable *only* by whoever had been handed its id out of band — the four-eyes ceremony on
  `sanctions.clear` completed only if the two operators were already talking, and the 24h Redis TTL
  expired the request silently otherwise. This is an availability gap in a compliance control, not a
  UX nicety: `rules.yaml` justifies this service's money-path entry on exactly that action.
  Risk class = **information disclosure** (a new list endpoint over approval metadata, C6 above) and
  **elevation of privilege** only in the sense that it makes the existing control usable — it grants
  no new authority, since deciding still goes through `PATCH` and the self-approval guard.
  `ApprovalResponse` additionally gained `makerId` and `createdAt`; both are approval metadata, not
  screening data. Rollback: revert; the endpoint is additive and nothing depends on it yet.
- **2026-08-02** — **C2 closed: `sanctions.clear` four-eyes approvals are now scoped to the check
  being decided.** `resource = ""` -> `resource = "#cmd.checkId"`. `AuthorizeInterceptor.satisfies`
  matches an approval on (action, resourceId, maker); with no resource, every approval a maker held
  satisfied every review they attempted, so one checker's decision authorised any number of others —
  reachable by replaying the same `X-Approval-Id` against a different `checkId`, requiring nothing
  clever of the maker. Recorded as a deferred residual while nothing drove the flow; #3465 gave it a
  UI, which is what moved it from theoretical to reachable.
  No policy change: `matrix-allows` (the rule granting `sanctions.clear`) keys on principal type,
  roles and action only, and no deny rule reads `input.resource` — verified against `rest.rego`
  before the change. The mechanism this now depends on was itself untested until the companion
  interceptor case in #3349 (an approval for resource A must not unlock resource B).
  Risk class = **elevation of privilege** (narrowed). Rollback: revert to `resource = ""`, which
  restores the over-broad grant — the two tests would go red first.
- **2026-09-08** — **New inbound REST surface: `POST /api/v2/sanctions/lists/refresh-all`** (issue
  #9048), and the v1 operation's semantics change under its unchanged shape. The old v1 behaviour
  was itself a D1-class hazard: it ran the entire external-feed fan-out synchronously inside the
  HTTP request, so any slow feed turned a compliance-data refresh into an opaque proxy 502/504 and
  the fuzz lane had to exclude the operation outright. Both URL majors now set a
  `refresh_requested_at` flag (V12, additive column) on every enabled list and return immediately —
  v2 with 202 + `{requested: N}` and a **required `Idempotency-Key` header** (#8351), v1 with its
  legacy 200 + array of pre-refresh lists for the deprecation window. The 60s scheduler treats a
  flagged list as due, runs the real imports one list at a time, and `markUpdated` clears the flag
  at commit, so a flag cannot cause re-import on every tick. Risk class = **denial of service**
  (a refreshed-all trigger is an expensive-batch amplifier): mitigated by `ROLE_OPERATOR`/
  `ROLE_ADMIN` + `@Authorize(sanctions.trigger)` on BOTH URL majors (regression guard extended to
  the v2 resource class), by the flag being idempotent by construction (repeated triggers converge,
  no dedup store needed), and by `ConcurrentExecution.SKIP` on the scheduler. No new egress — the
  same feeds the cron already fetches. Rollback: revert; V12 is a nullable additive column and the
  v1 path is untouched in shape.

- **2026-09-04** — Issue #8362: first-party EU consolidated-list adapter + import outcomes.
  The EU list import switches its default source from the OpenSanctions-normalised CSV mirror
  (migration V7's workaround for a then-redirecting endpoint) to the official EU FSF XML feed,
  SAX-streamed (`EuFsfSaxParser`, ~25 MB, O(1) memory per entity); `opensanctions` stays
  selectable for rollback and `seed` keeps the Flyway sample entries as an explicitly
  NON-PRODUCTION local-dev fallback (`%dev` profile). The threat this closes is silent
  staleness masquerading as health: the import previously returned a bare count where `0`
  meant failed, skipped AND legitimately-empty at once, so a feed that had been failing for
  weeks read identically to a working one (#4348 shape). Every import attempt now resolves to
  a named `ListImportOutcome` (`imported` | `empty_feed` | `failed_kept_existing` |
  `skipped_not_entity_based` | `seed_fallback_non_production`) and increments
  `openbank.sanctions.list.imports{list_type,outcome}` — alert on the absence of
  `outcome=imported`, never on an error rate. The durability contract is unchanged:
  deactivateMissing runs only after a fully-consumed stream, so a mid-stream failure keeps the
  previously stored entries (#1432). No endpoint, authz or DB schema change; rollback = revert
  the commit or set `SANCTIONS_EU_SOURCE=opensanctions`.

- **2026-09-27** — **ApprovalResource migrated onto ApprovalEndpointSupport (#10917/#11031/#11062),
  no wire change.** The maker-checker four-eyes endpoints (`GET .../approvals`,
  `PATCH .../approvals/{id}`) now delegate their body — limit clamping, the null-body-is-400
  guard (#3029), unknown-id-is-404, checker-identity resolution and the self-approval refusal — to
  the shared `ApprovalEndpointSupport` (libs-runtime). Only the `@Path`/`@RolesAllowed`/
  `@Authorize` annotations and the Quarkus resource class stay per-service. Paths, roles, status
  codes and JSON field names are unchanged; ApprovalResourceMappingTest (since replaced by the shared `ApprovalEndpointConformance` suite) covers the mapping.
  **Risk class:** none — response-plumbing de-duplication only; the self-approval check (a maker
  cannot approve their own request) is preserved verbatim in the shared implementation, and a
  maker-cannot-approve-own test exists for this service. Rollback: revert to the inline
  implementation this PR replaces.

- **2026-09-28** — **`ApprovalEndpointSupport.decide()` now resolves the checker identity lazily,
  after the null-body check (#11047/#11061).** The prior parameter was a bare `SecurityIdentity`,
  which looked like it deferred `checkerId()` resolution past `requireNotNull(request)` but did
  not: Kotlin evaluates a call's argument expressions before the function body runs, so passing a
  caller's `lateinit var identity` as that argument threw UninitializedPropertyAccessException at
  the call site whenever the body was null — before `decide()` ever reached its own null-body
  guard, inverting the documented and tested "null body rejected before any identity is resolved"
  contract (`ApprovalNullBodyTest`, #3029). `decide()` now takes an
  `identityProvider: () -> SecurityIdentity` supplier; this service's call site is migrated to
  `support.decide(id, request) { identity }`, and the supplier is invoked only after
  `requireNotNull(request)` returns. **Risk class:** none — fixes an incorrect 500
  (uninitialized-property crash) on a malformed request back to the intended 400; no endpoint,
  authorization, self-approval or wire-shape change. Rollback: revert to the eager
  `SecurityIdentity` parameter.

## 2026-10-04 — Staged Envoy Gateway public edge (ADR-0324 Phase 4)

The `/api/v1/sanctions` HTTPRoute is staged beside `sanctions-api` on ingress-nginx.
The generated `sanctions-service-ingress-allow-list` adds `envoy-gateway-system` on TCP
8123 while retaining nginx. DNS still points to nginx; this introduces an internal proxy
peer now, with public traffic changing only after the coordinated cutover. The service's
OIDC, OPA and sanctions decision logic remain the authorization and compliance boundary.

**D1 residual risk at cutover:** the nginx per-client-IP cap of ten concurrent connections
has no Envoy Gateway equivalent. The 20/s per-client request rate, per-backend circuit
breaker and separately staged listener-wide connection limit bound aggregate load, but
slow connections from one client can consume more than ten slots and delay other screening
requests. Verify the listener policy and 401/429 behavior on the Gateway address before
moving DNS. Roll back all four `api.open-bank.tech` routes together to nginx; its Ingress
and network allowance remain until Phase 5.

- **2026-10-17** — **Listener and header config now inherited, values unchanged (#13137).** This service's `application.yaml` no longer declares the HTTP security response headers (`quarkus.http.header.*`) and the management listener (`quarkus.management.*`: port 8085, host 0.0.0.0, root `/q`). The same values now come from the fleet default `openbank-libs-runtime/src/main/resources/application.yaml`, which is on the classpath at a lower precedence than the service file. **No trust-boundary change:** the effective per-key config was asserted identical before and after, and a `@QuarkusTest` config dump on ledger-service matched key for key. **Residual risk:** a later edit to the shared default now changes this listener and these headers too; such an edit is a fleet-wide boundary change and must update every affected threat model. Rollback: revert the commit.

## 2026-11-11 - Label key domain rename (no reach change)

The NetworkPolicies that admit or reach this service select pods by `open-bank.tech/*` label keys
(`plane`, `money-path-service`, `allow-internet-egress`, ...) instead of `openbank.io/*`. The project
does not own the `openbank.io` domain. The selectors and the pod labels they match move in the same
change, so every allowed peer, port and direction is unchanged: no new caller, no new egress, no new
listener. Residual risk: for the length of one Argo sync, a policy and the pod it selects can briefly
carry different keys. NetworkPolicy fails closed (unselected traffic is denied), so a mismatch drops
traffic rather than widening it.
