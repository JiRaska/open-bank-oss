---
date: 2026-11-17
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, ai-agents, fraud, privacy-gdpr]
summary: "Customer MCP opens only once observable: hash-linked audit, closed-label metrics, warehouse facts under per-party shreddable keys, single-actor client suspend and connection revoke, four-eyes unsuspend and bulk revoke."
---

# ADR-0384 — Customer MCP channel observability: bounded telemetry, admin view, warehouse history and abuse signals before the route opens

## Context

**What this ADR is for.** ADR-0383 (proposed/planned, on `main` since PR #16591) decides
how a customer admits a third-party MCP client: an `MCP_CLIENT` delegation grant, DPoP-bound
tokens, layered throttling, and proposals that execute only after SCA in the bank app. It decides
the controls. It does not decide how anyone sees whether the controls carry work, how the channel
performs, who holds access, or whether it is being abused. Issue #16605 (sub-task of #16590) asks
for that. This ADR decides it, and makes it a precondition of ADR-0383's "Exposure last" step.

**Adjacent decisions and why they do not cover this.**
- ADR-0077 / ADR-0087 / ADR-0088: the metrics, traces, logs, alerting and Pyrra SLO-as-code
  substrate. They say *how* to instrument; nothing about this channel's signals, labels or alerts.
- ADR-0226: every audit event carries `channel` (`ui|mcp|api`), `act_chain` and `session_id`. It is
  the per-action trail. It is not a metric, not a warehouse fact, and (see below) mcp-service does
  not yet send it to a store.
- ADR-0022 / ADR-0023: event-fed ClickHouse medallion, bronze as the 10-year log of record, PII
  masked at the sink, crypto-shredding by per-subject key. They decide the pipeline, not which
  channel facts enter it or under what retention.
- ADR-0286: the admin UI reads the warehouse only through a governed query registry over gold
  marts, plus embedded Grafana for exploration. This ADR adds entries; it does not change the rule.
- ADR-0056: the BFF is the sole browser→cluster path. Applies unchanged.
- ADR-0118: PII classes and retention (session/access logs 90 days, audit 5 years, ledger 10
  years). This ADR classifies the new facts against it.
- ADR-0189 / ADR-0378 D5: keyed pseudonyms are computed in one place with a managed secret; an
  unkeyed hash of a small identifier space is pseudonymisation in name only.
- ADR-0084 / ADR-0377 / ADR-0378 / ADR-0139: fraud-service scores *value movements*; callers send
  one typed risk context; account-control events feed the next decision but are not admitted; new
  logic starts in shadow.
- ADR-0237: scheduled jobs and feeds register the shared liveness gauge.
- ADR-0252: bank-owned synthetic customers drive real journeys, tainted end to end; the taint
  **excludes** them from analytics baselines and **includes** them in every control path.
- ADR-0303 / ADR-0304: the context graph and the fraud investigation lens.
- ADR-0228 (proposed/planned): unified entity resolution. Not built; this ADR does not depend on it.
- ADR-0155 / `rules.yaml: four_eyes`: two-phase approval via an `ApprovalStore`.

None of them decides the channel's observable surface. ADR-0383 explicitly relies on "anomaly
alerts" as a mitigation for a stolen DPoP key plus refresh token, and nothing defines them.

**What the repository holds today, and where it contradicts the issue's starting design.**
Each point was checked against `origin/main` on 2026-11-17.

1. **MCP already emits bounded metrics; a second family would split one channel into two.**
   `McpMetricsAdapter` emits five series: `openbank_mcp_requests_total{method}`,
   `openbank_mcp_tool_calls_total{tool,decision,result}`,
   `openbank_mcp_tool_call_duration_seconds{tool}`,
   `openbank_mcp_caller_identity_total{source}` and `openbank_mcp_tools_list_total{outcome}`, all
   `service="mcp"`, with `tool` mapped to a bounded
   value and no agent id, consent id or argument as a tag. The issue's
   `openbank_mcp_customer_calls_total{tool,outcome}` would duplicate it. The repo wins: the
   existing series gain a `channel` label instead.
2. **The durable audit publisher exists; mcp-service does not use it.** `McpCallAuditor` builds the
   ADR-0226 envelope (`channel = mcp`, `actChain`, `sessionId`, payload with argument *keys* only).
   libs-runtime carries two publishers. `LoggingAuditEventPublisher` is the `@Default`, and its log
   line carries `eventId, at, actor, actorType, op, resource, resourceId, result, traceId`, but not
   `channel`, `actChain` or `payload`. `HashLinkedOutboxAuditEventPublisher` (ADR-0323 phase 1) is
   an opt-in `@Alternative`, selected by the build property
   `openbank.audit.publisher=hash-linked-outbox`. A service that opts in must supply an
   `AuditChainOutbox` bean, which appends under a per-producer DB lock in one transaction, and an
   outbox dispatcher to relay to the audit topic. mcp-service has not opted in, so its audit events
   end as the reduced log line. D0 makes opting in a prerequisite.
3. **mcp-service has no outbox and no Kafka.** Its tree holds `McpRateLimiter` and
   `UnwiredProposalPort`, a Postgres database `openbank_mcp`, and no `outgoing` channel. A per-call
   event "over the existing outbox" does not exist to reuse; it must be built (ADR-0003).
4. **delegation-service's grant lifecycle topic has six consumers, and its schema assumes a party
   grantee.** `openbank.delegation.events` carries `DelegationOffered`, `DelegationActivated`,
   `DelegationRevoked`, `DelegationExpired`, `DelegationSuspended`, `DelegationReinstated` and more.
   It is consumed by account-service, card-issuance-service, notification-service,
   tax-reporting-service, context-service and audit-service. analytics-sink does not consume it.
   The AsyncAPI contract (`openbank-contracts/openbank-delegation-service/asyncapi.yaml`) requires
   `grantorPartyId` and `granteePartyId` as UUIDs, and Pacts exist between delegation-service and
   account-service and card-issuance-service. An `MCP_CLIENT` grant has no grantee party:
   PR #16599 adds `DelegationGranteeType { PARTY, MCP_CLIENT }` with `PARTY` as the default. The
   issue's `connected` / `revoked` / `expired` are these lifecycle facts with a grantee type, but
   putting them on this topic would hand every existing consumer a grant it cannot interpret.
5. **Refresh-token family revocation is not a delegation-service fact.** ADR-0383 D2 puts refresh
   rotation and reuse detection in the customers realm. delegation-service never sees a refresh
   token, so it cannot emit `refreshed-family-revoked`. That signal comes from the realm's event
   stream or from MCP's own token rejections, and this ADR takes the second (D2).
6. **Synthetic activity is deliberately absent from silver and gold.** `V9__synthetic_provenance.sql`
   adds `bronze_events.synthetic` and filters `WHERE synthetic = 0` in `silver_current_state`,
   `silver_history`, `silver_as_of` and `gold_daily_event_volume`. "A synthetic customer shows up in
   every view" is therefore false by design for the warehouse, and must stay false: ADR-0252 says
   the taint excludes synthetic activity from analytics baselines. The delivery checks below assert
   presence where the taint says *include* and absence where it says *exclude*.
7. **The deployed warehouse cannot crypto-shred.** analytics-sink selects its `CryptoErasure`
   backend at build time from `ANALYTICS_ERASURE_BACKEND`; unset means `NoOpCryptoErasure`, whose
   outcome is `NO_BACKEND`. No gitops manifest sets it. "Party erasure crypto-shreds the pseudonym
   key" is a prerequisite, not a property of today's estate.
8. **fraud-service has no `HOLD` verdict and no MCP channel value.** `FraudVerdict` is
   `ALLOW | CHALLENGE | REVIEW | DECLINE | UNKNOWN`. In ADR-0084, `REVIEW` holds the payment and
   opens a case; ADR-0374 maps a hold to its `HELD` state. `FraudRiskContext` (libs-fincrime) has
   closed `InitiationMode` and `InitiationChannel` sets, neither naming MCP. The customer approves
   in the app (ADR-0383 D6), so `CUSTOMER_PRESENT` / `MOBILE_APP` is the truthful value; where the
   instruction came from is missing evidence, and adding it is a contract change (D5, which also argues `CUSTOMER_PRESENT` over `DELEGATE`).
9. **Scraping and key reuse are not fraud-service decisions.** ADR-0377 D1 scopes fraud decisions
   to value movements. A read sweep moves no value. Those detections belong to the channel's own
   abuse analytics and alerts, with a human acting on them (D3, D4), not to a fraud verdict.
10. **Customer self-revoke is exempt from four-eyes, and must stay so; the operator actions are
    not built.** `rules.yaml` defers four-eyes enforcement for delegation-service (expiry
    2027-03-10) because `delegation.revoke`, `delegation.disclosure.release` and `delegation.reserve.release` are granted
    to the edge M2M identity and the service wires no `ApprovalStore`. The `ApprovalStore` is
    before-effect only: it parks a request until a second person approves. The `rules.yaml` pattern for this case (the SDD note) is that an operator path
    gets its own distinct action, and that action is gated.
11. **A zero-traffic alert on real traffic is a standing false alarm at launch.** ADR-0241's goal is
    zero standing criticals. The repo's liveness answer for a customer-visible capability is a
    journey in `openbank-libs/governance/journeys.yaml`, run by a CronJob and held by the enforced
    `journey-catalog-integrity` and `journey-money-path-accountability` gates (ADR-0252 phase 2).
12. **There is no per-service SLO schema.** `rules.yaml: slo` is one fleet-wide target
    (availability 99.9 % and latency 99 % under 1 s, both over 30 days), and `check-slo-registry.py`
    applies it to every `money_path_services` entry in `pyrra-slo-money-path.yaml`. mcp-service is
    not money-path. A per-service SLO entry and the matching checker extension are new work.

**Deadline.** None regulatory. The binding order is ADR-0383's: no route to mcp-service until its
delivery checks hold. This ADR adds its own checks to that list.

## Decision

We will make the customer MCP channel observable before it is reachable, using the estate's
existing signal paths, and we will not open the ADR-0383 route until every delivery check below
passes in the sandbox.

### D0 — Prerequisite: mcp-service opts in to the hash-linked audit publisher

mcp-service sets `openbank.audit.publisher=hash-linked-outbox` and supplies the two parts the
libs-runtime publisher needs: an `AuditChainOutbox` over its own `openbank_mcp` database (outbox
table plus chain-head row, appended under a per-producer advisory transaction lock), and an outbox
dispatcher (ADR-0013 / ADR-0050, `dispatch-enabled: true`) relaying to the audit topic with the
usual DLQ, `KafkaTopic` CR and ACLs. This is mcp-service's first outbox. ADR-0323 is
`proposed/partial`, and its phase-1 publisher is what is being adopted.

**Cost.** Every audited MCP call takes the per-producer lock, so audit writes of one mcp-service
deployment are serialised: the chain's append rate is a ceiling on audited calls. Before the
route opens, the ADR-0383 D5 limits are set below a measured append rate, from a perf scenario
`mcp-audit-chain-append` in `perf/scenarios.yaml`. Tool-call telemetry (D2) uses plain outbox rows
in the same database, **not** the chain, so it never waits on the lock.

**Until mcp-service's audit events land in audit-service with `channel` set and an unbroken chain,
the route stays closed.**

### D1 — Live metrics: extend the existing MCP series; labels are closed sets

- All five existing MCP series gain `channel ∈ {agent, operator, customer}`, derived from the
  ADR-0383 D1 resolution path (`iss` + path), never from a token claim the client controls.
- **Tag keys are identical across channels.** Prometheus accepts a meter name with only one tag-key
  set, so every series that carries `client` and `synthetic` carries them on every channel:
  agent and operator calls emit fixed placeholders `client="none"` and `synthetic="false"`.
- Customer-channel series additionally carry `client`. This is allowed **only because** ADR-0383
  D1 admits allowlisted, bank-registered clients with dynamic registration off, so the value set is
  the allowlist. The value is the allowlist entry's short id from mcp-service config; anything else
  is `unlisted`. The allowlist is capped at **25** entries, because above that `client` stops
  being a safe label and per-client views must move to the warehouse. The cap is enforced where
  the allowlist is loaded and at client registration: mcp-service refuses to start with more than
  25 entries, and the registration tooling refuses a 26th. Both are proven by tests (Delivery
  check). Registering a 26th client therefore needs a superseding decision, not a
  config edit.
- Customer-channel series carry `synthetic ∈ {true,false}` from the ADR-0252 taint (fail-to-real,
  `SyntheticTaint.isTainted`), so the synthetic journey's outcome is readable as the availability
  signal and real-traffic panels can exclude it.
- **Never a label:** party id or pseudonym, grant id, `jkt`, `sub`, session id, IP, IBAN, amount,
  argument value, error message. A party is never a Prometheus dimension.
- New customer-only counters, all closed enums:
  - `openbank_mcp_customer_rejected_total{layer,reason,client,synthetic}` —
    `layer ∈ {edge_unauth, token, dpop, grant, throttle, policy, downstream}`;
    `reason` from a fixed enum per layer (e.g. `dpop`: `missing, bad_sig, htu_mismatch, ath_mismatch,
    stale_iat, replay, jkt_mismatch, nonce_required, replay_store_unavailable`;
    `throttle`: `unauth_bucket, party_client_bucket, party_daily, concurrency, downstream_budget,
    circuit_open`).
  - `openbank_mcp_customer_refresh_family_revoked_total{client}` is **not** built; family revocation
    happens in the realm (Context 5). MCP counts what it sees: `layer="token", reason="revoked_grant"`
    and `reason="expired"`.
  - `openbank_mcp_customer_proposals_total{stage,client,synthetic}`,
    `stage ∈ {created, deduplicated, refused_limit, approved, rejected, expired}`, and
    `openbank_mcp_customer_proposal_decision_seconds{outcome}` (histogram, `standardPercentiles`).
    **This part depends on the ADR-0383 D6 proposal store**, which does not exist today
    (`UnwiredProposalPort`). The proposal-funnel signals (these series, the D2 proposal events,
    `gold_mcp_proposal_funnel` and the D6 rejection alert) are delivered with that store, and
    their delivery status follows it. They are not invented ahead of it.
  - `openbank_mcp_customer_replay_store_up` (0/1) — the fail-closed dependency, scraped, not inferred.
- Gauges from a delegation-service projection, not from MCP and never per party:
  `openbank_delegation_mcp_active_grants{client}` and `openbank_delegation_mcp_connected_clients`.
  They are computed by a `@Scheduled` count, which therefore registers
  `registerWorkflowLiveness("mcp-grant-projection", …)` (ADR-0237; the enforced
  `scheduler liveness adoption` gate already requires it).

### D2 — Facts at the source

- **Connection lifecycle on its own topic.** Lifecycle facts for `MCP_CLIENT` grants go to a new
  topic `openbank.delegation.mcp-client-events`, with its own AsyncAPI channel and schema version,
  and **never** to `openbank.delegation.events`. The existing topic's schema and Pacts are
  unchanged, so this is backward compatible by construction. As defence in depth, every existing
  consumer of `openbank.delegation.events` ignores a record whose `granteeType` is not `PARTY`,
  using the guard PR #16599 introduces, with a consumer test each. The new topic carries
  `Activated / Revoked / Expired / Suspended / Reinstated` with grantor party id, `granteeType`,
  allowlisted client id, capabilities, `expiresAt`, a revocation `reasonCode` from a closed set
  (`customer, operator, client_kill_switch, client_bulk_revoke, expiry, family_revoked_reported`),
  and `jktRef`. It also carries the per-client `McpClientSuspended / McpClientUnsuspended` facts
  (D4). `jktRef` is an HMAC of the thumbprint under a delegation-service key; the raw `jkt` stays
  only on the grant row, where ADR-0383 D3 needs it. Consumer Pacts are added for analytics-sink
  and fraud-service.
- **Tool calls:** through the D0 outbox (plain rows, SENT purge per ADR-0329), mcp-service publishes one `mcp.customer.tool-call.v1` fact on topic
  `openbank.mcp.customer.tool-calls` per
  customer-channel `tools/call`, **after** the ADR-0383 D5 token step (tokenless floods are counted
  in D1, never written per request). Fields: event id, occurred-at, client id, party id (raw, on the
  authenticated in-cluster hop only), tool, capability, decision, outcome, reject layer/reason,
  latency, response bytes, page count, the requested window length in days, and the synthetic
  header. **Never** argument values, response bodies, IBANs, amounts or free text.
  - The topic follows the fleet's DLQ rule: explicit nested `dead-letter-queue: topic:`, a
    `KafkaTopic` CR, and Write/Read ACLs (CLAUDE.md, #5745).
  - This fact is **telemetry, not audit.** The ADR-0226 audit event stays the per-action record and
    is not replaced by it (D0).
- **Proposals:** the ADR-0383 proposal store emits `proposal.created|approved|rejected|expired`
  with client id, party id, `createdAt`, `decidedAt`. No payee, amount or reference on this topic;
  those live on the proposal row and reach fraud-service through D5.

### D3 — Warehouse history

- analytics-sink adds `openbank.delegation.mcp-client-events`, the tool-call topic and the proposal
  topic to its explicit topic list, with Read ACLs.
- **Pseudonymisation at the sink: a new keyed party reference.** Nothing on `main` creates or uses
  per-subject keys for HMAC. `VaultCryptoErasure` only *destroys* a Transit key named
  `analytics-<aggregateType>-<aggregateId>`, using `keyName` sanitisation. The scheme below is new
  design:
  - **One key holder.** analytics-sink is the only service that holds or uses the key. For a party
    it creates, on first use, the Transit key `analytics-party-<partyId>`: exactly the name
    `VaultCryptoErasure.keyName(AggregateKey("party", partyId))` destroys, so existing erasure
    reaches it with no new code path.
  - **Derivation.** `party_ref = Transit HMAC-SHA-256(key = analytics-party-<partyId>, input =
    partyId)`, computed by Vault; the key material never leaves Vault. The sink replaces every party
    id on the three channel topics with `party_ref` before writing, so raw party ids never land in
    ClickHouse for this channel.
  - **Only ingest creates keys.** A key is created when the first channel event for a party is
    ingested, and nowhere else. The lookup endpoint below is derive-only.
  - **No cache that outlives the key.** The sink keeps an in-memory `partyId → party_ref` map with
    a 15-minute TTL, lost on restart. The erasure path evicts the entry before it destroys the key.
    So after erasure, at most the in-flight batch is written under the old ref.
  - **No silent re-creation after erasure.** Before creating a key, the sink reads an
    `erased_subjects` table, which is new.
    - **Contents.** Raw party id, aggregate type and erased-at time.
    - **When it is written.** `ErasureService` writes the row on `ERASED`, **before** it destroys
      the key.
    - **How it is read.** Read-through on every key creation, with no cache. So there is no
      window in which an erased party can be keyed again.
    - **Effect.** An erased party gets `party_ref = NULL`: its new events are counted in aggregates
      and linked to nothing. A re-onboarded person is a new party id and a new key.
    - **Classification (ADR-0118).** A compliance tombstone: the record that an erasure was done.
      It has the same class and retention as the `ErasureDecision` audit record, which ADR-0023
      makes the record of what was erased.
    - **Containment.** It is sink-internal. It is never exposed through the query registry,
      Grafana, a mart or the lookup endpoint, and analytics-sink's ClickHouse grants give other
      readers no access to it.
    - **Backup and replication.** It follows the warehouse's own backup and replication. A restore
      that lost it would lose erasures, so the restore runbook checks it against audit-service's
      `ErasureDecision` records.
    - **Exempt from further erasure.** Erasing the tombstone would undo the erasure it guards,
      under GDPR Art. 17(3)(b), the legal-obligation hold ADR-0118 already applies, to demonstrate
      that the erasure was carried out.
  - **Connections lookup without a second key holder.** The admin Connections view does not
    compute `party_ref`. The BFF calls one analytics-sink endpoint,
    `GET /api/v1/mcp/party-activity/{partyId}?purpose=<closed enum>`.
    - **Who may call it.** analytics-sink runs no OPA sidecar, so a caller guard is the
      authorization. It is modelled on `CreditProfileCallerGuard`: a pinned principal-id set,
      checked by a test against the caller's own client configuration. The machine caller must be
      `service-account-openbank-admin-ui`, identified by principal id per the CLAUDE.md OPA rule,
      never by principal type.
    - **Which operator.** The request must also carry the operator's own staff-realm token, and the
      guard requires the role that `ErasureResource` already uses for compliance reads
      (`Roles.COMPLIANCE`, or `Roles.ADMIN`). The BFF additionally checks the admin-ui permission
      `compliance:view` before calling.
    - **Audit.** Every call, allowed or denied, writes an audit event with the operator, the
      machine caller, the `partyId` and the purpose.
    - **Derive-only.** The lookup never creates a key. If `analytics-party-<partyId>` does not
      exist, the answer is "no activity". The same is true for a party in `erased_subjects`.
    - **The query.** One fixed parameterised query over `silver_mcp_tool_calls`, with no SQL from
      the caller. It is the **sole per-party read exception** to ADR-0286's registry-only path, and
      it exists only because the sink is the only key holder.
  - **Prerequisite.** Until `ANALYTICS_ERASURE_BACKEND=vault` is deployed (Context 7), these topics
    are **not** ingested. A `NO_BACKEND` sink must not start collecting party-keyed channel history.
  - **Residual linkage, stated.** Erasure severs `party_ref`, but `jktRef` plus event timestamps on
    the retained lifecycle rows can still be joined to delegation-service's grant row (party, jkt,
    times) by anyone who holds both, until delegation-service's own erasure handles that row
    (ADR-0118 table). The warehouse alone cannot re-link; the bank as a whole can, for as long as
    the grant record is retained.
- **Retention by class (ADR-0118), not by the bronze floor.** Tool-call facts are access logs:
  they go to a dedicated `silver_mcp_tool_calls` table with `TTL occurred_at + INTERVAL 90 DAY`,
  **not** into `bronze_events`, whose 10-year TTL is a floor that cannot be lowered (ADR-0022 §5).
  Lifecycle and proposal events are delegation and instruction records and enter `bronze_events`
  as usual.
- **Gold marts** (ClickHouse migrations, `WHERE synthetic = 0` like V9):
  `gold_mcp_channel_daily` (calls, rejects by layer/reason, p95 from stored latency, by client);
  `gold_mcp_client_scorecard` (per client: active grants, calls, reject mix, proposal approval
  rate); `gold_mcp_connection_cohorts` (by connect week: retention, revocation `reasonCode`);
  `gold_mcp_proposal_funnel` (stage counts, time-to-decision); `gold_mcp_abuse_indicators` (below).
  The first four marts aggregate across parties and carry no `party_ref`, so they fall outside the
  90-day class and keep the warehouse default.
- **Abuse indicators** are measurements, not judgements (V15's posture), with thresholds as
  registry parameters: distinct `party_ref` per `jktRef` (> 1 means a key reused across parties);
  new grants per client per 10 minutes; read-sweep score per `party_ref` and day (pages × window
  days over the tool cost table); proposals created per `party_ref` and day against the ADR-0383
  D6 limits; reject-ratio by client.
  `gold_mcp_abuse_indicators` is a materialised table with `TTL day + INTERVAL 90 DAY`, because
  its per-party rows carry `party_ref`. It exposes `party_ref` only for rows above a threshold,
  only under `compliance:view`, which is the admin-ui permission that already covers `/fraud` and
  `/aml` in `roles.ts`.

### D4 — Admin UI: one page, four reads, actions governed by direction

A `Customer MCP` page under the existing `/delegations` area. Every request goes through the BFF
(ADR-0056).
- **Overview** — embedded Grafana dashboard-as-code over D1 (ADR-0286 exploration path): calls,
  rejects by layer, p95, SLO burn, replay-store up, active grants, proposal funnel. Real traffic by
  default, with a synthetic toggle.
- **Clients** — one row per allowlisted client from mcp-service config, joined with
  `gold_mcp_client_scorecard` via the query registry (permission `delegations:view`), plus its
  suspension state.
- **Connections** — party lookup through the existing party search and
  `/api/delegations/effective-access/[partyId]` (ADR-0228 is not a dependency), showing the party's
  `MCP_CLIENT` grants and, from the analytics-sink endpoint (D3), the last 90 days of that party's
  tool-call counts by tool and outcome. The lookup is audit-stamped as a staff read of customer
  data.
- **Abuse** — `gold_mcp_abuse_indicators` via the registry, permission `compliance:view`, with a
  link into the ADR-0304 fraud lens when a case exists.
- **Actions, governed by direction** (ADR-0047 point 6: tightening and halting is single-actor
  break-glass; loosening and resuming needs four-eyes; break-glass actions are also recorded as
  DORA-reportable operational events with a mandatory, time-boxed post-hoc review by an independent
  function). Every action below is new and distinct (Context 10), takes a reason code, and emits an
  audit event.
  - `delegation.operatorRevoke` revokes one grant. **Single actor** (operator role), effective
    immediately, audited. It only reduces access.
  - `delegation.mcpClientSuspend` is the emergency stop for one client. **Single actor** (operator
    role), effective immediately.
    - **State.** A new `McpClientSuspension` aggregate in delegation-service, keyed by `azp`, with
      state `ACTIVE | SUSPENDED`, actor, reason and time. It is separate from per-grant
      `DelegationSuspended`: grants are not touched, so lifting the suspension restores exactly the
      grants that existed.
    - **Effect.** mcp-service needs no new read path. The ADR-0383 D3 per-call grant lookup in
      delegation-service answers "no grant" while the grant's `azp` is suspended, and MCP rejects
      with `layer="grant", reason="client_suspended"`. The `McpClientSuspended` fact (D2) feeds the
      warehouse and the page.
    - **Post-hoc review (new mechanism).** The suspension writes, in the same transaction, a
      `BreakGlassReview` record: `OPEN`, a 24h due time, and the suspending actor. A different
      operator closes it as `CONFIRMED` or `DISPUTED`; the domain refuses the suspending actor. It
      is not an `ApprovalStore` item, because nothing waits on it. A gauge
      `openbank_delegation_breakglass_review_overdue` (count of `OPEN` items past due) drives a
      warning alert. `DISPUTED` does not lift the suspension; lifting is the four-eyes action below.
    - **DORA event.** ADR-0047 requires break-glass actions to be recorded as DORA-reportable
      operational events. The suspension emits that record as an audit event with operation
      `breakglass.mcpClientSuspend`. Which register holds it is decided with ADR-0047's Tier-A
      break-glass queue, which ADR-0047 itself lists as not yet wired.
  - `delegation.mcpClientUnsuspend` (lifting a suspension) and `delegation.mcpClientBulkRevoke`
    (permanently revoking every grant of a client) are **four-eyes before effect** (ADR-0155). They
    land in `rules.yaml: four_eyes.actions`, and delegation-service wires the shared
    `ApprovalStore`.
  - **The four-eyes interaction.** Wiring an `ApprovalStore` is what the delegation-service
    deferral waits for, so the same change must keep customer self-service flowing. It adds a
    `four_eyes.exemptions` entry `delegation.revoke` for the edge's verified service account (the
    principal behind the `edge-service-delegation` grant), and the same for
    `delegation.disclosure.release` and `delegation.reserve.release`, the other two actions the deferral names. It also replaces the
    delegation-service deferral entry. Customer self-revoke therefore stays single-party and is
    never parked.

### D5 — Fraud: evidence at approval, account-control events, shadow first

- **`CUSTOMER_PRESENT`, not `DELEGATE`.** `DELEGATE` means "instructed by a person acting under a
  delegation granted by the holder". The MCP client holds a delegation, but it does not instruct:
  its grant carries no payment capability (ADR-0383 D3). The holder authenticates in this session
  and authorises with SCA in the app (ADR-0383 D6), and the ADR-0375 Authority step sees the
  customer as the actor. `DELEGATE` would misstate who instructed, and it would point a
  delegated-authority check at a grant that cannot authorise anything. The proposal origin is
  carried as evidence instead.
- **At approval** the payment is scored like any app payment, with `initiationMode =
  CUSTOMER_PRESENT`, `channel = MOBILE_APP`, `vopResult` from ADR-0171, and one new optional typed
  evidence field in `FraudRiskEvidence`: `origin: InstructionOrigin? ∈ {MCP_CLIENT_PROPOSAL}` with
  `originClientId` (allowlisted) and `proposalAgeSeconds`. Closed set, so it is a contract change
  in libs-fincrime with its Pact update.
- **Account-control events** (ADR-0377 D4): an `MCP_CLIENT` `DelegationActivated` reaches
  fraud-service as a credential-like change, so "payment shortly after a new client connection" is
  a recent-change age the pack can read. It never blocks the connection itself.
- **Verdicts are the existing ones.** `CHALLENGE` adds friction at the approval screen; `REVIEW`
  holds (ADR-0084, ADR-0374 `HELD`); `DECLINE` refuses. An MCP-originated proposal is never an
  exemption and never lowers a verdict.
- **Shadow first.** Rules that read `origin` run in shadow until a baseline exists (ADR-0139,
  ADR-0354 packs). Synthetic approvals are scored (control path includes them) and excluded from
  the learning baseline (ADR-0252, ADR-0378 gated learning).
- Read-sweep and key-reuse indicators (D3) are **not** fraud inputs. They drive alerts and a human
  decision (D4 actions); a later ADR may promote one into a feature only through ADR-0378's gate.

### D6 — Alerts and SLO

PrometheusRules, warning unless stated, each with a runbook line:
- `McpCustomerReplayStoreDown` — `replay_store_up == 0` for 2m, **critical** (the channel is
  failing closed: every customer request is rejected).
- `McpCustomerDpopRejectSpike` — DPoP rejects per client over 5m above a ratio of that client's
  calls (excluding `nonce_required`, which is normal protocol).
- `McpCustomerClientErrorRate` — one client's `downstream` + `policy` reject ratio above the
  others' by a fixed factor.
- `McpCustomerProposalRejectionHigh` — rejected / decided above a threshold over 24h.
- `McpCustomerGrantProjectionStale` — via ADR-0237's existing rule pattern.
- **Liveness is the journey, not zero traffic:** a `mcp-customer-read` journey in
  `journeys.yaml` (status `active`, severity `ticket`, `money_moving: false`) connects a synthetic
  party's client through the full DPoP path, calls one read tool and one `propose_payment`, and
  rejects the proposal in the app API. The enforced journey gates then hold it.
- **SLO:** availability and p95 latency of customer-channel read tools, as Pyrra objects. Because
  `rules.yaml: slo` is one fleet target (Context 12), this needs **new planned work**: a per-service
  SLO schema in `rules.yaml` and a `check-slo-registry.py` extension that holds an mcp-service
  object to it, rather than a hand-edited CR (see Enforcement). Synthetic traffic counts toward it (ADR-0252: the
  canary's outcome is the availability metric).

### D7 — Order

ADR-0383's "Exposure last" check gains: every ADR-0384 delivery check below passes in the
sandbox. Until then no HTTPRoute or Ingress names mcp-service.

## Alternatives considered

- **A separate `openbank_mcp_customer_*` call family (the issue's design).** Clean separation of the
  new channel. Rejected: the same tool call would appear in two families with two outcome
  vocabularies, and every agent-vs-customer comparison would need a join across them. A `channel`
  label on the existing series answers the same questions with one definition.
- **Party pseudonym as a Prometheus label, or per-party live metrics.** Lets the admin page show a
  party's live call rate. Rejected: unbounded cardinality, and a pseudonym in a 30-day metrics store
  outside the erasure model. Per-party history lives in the warehouse, keyed by a shreddable ref.
- **Derive tool-call history from the audit trail instead of a telemetry fact.** One stream fewer.
  Rejected: audit retention (5 years), immutability and the chain lock are wrong for high-volume
  access telemetry (90 days).
- **MCP_CLIENT lifecycle on `openbank.delegation.events` behind a `granteeType` guard.** One topic.
  Rejected: six consumers and two Pacts assume a party grantee. A guard missing in any one of them
  misreads a client grant as a party grant, and the schema's required `granteePartyId` has no
  honest value.
- **One global HMAC key for `party_ref`.** Simple and cheap. Rejected: it cannot be destroyed per
  party, so erasure could not sever one subject.
- **Tool-call facts in `bronze_events` like every other event.** Uniform ingestion. Rejected: bronze
  keeps 10 years as a floor, and access logs are a 90-day class under ADR-0118.
- **Score scraping and key reuse in fraud-service.** One risk engine. Rejected: ADR-0377 scopes
  fraud decisions to value movements, and a read has no admission step to attach a verdict to.
- **Four-eyes before effect for the per-client suspension.** Symmetric and simple. Rejected: it
  delays the stop during an active abuse incident. Suspension only reduces access and is
  reversible, so it follows ADR-0047's single-actor break-glass shape with a mandatory post-hoc
  review. The risk-increasing directions (unsuspend, and permanent bulk revoke that cannot be
  undone) stay four-eyes before effect.
- **A zero-traffic alert as the liveness signal.** Simple. Rejected: at launch real traffic is
  sparse, so it would page standing (ADR-0241); the synthetic journey proves the path end to end.

## Consequences

**Positive**
- Every ADR-0383 control (DPoP, replay store, throttles, grant check, PDP, proposal limits) has a
  counter that proves it carried work (ADR-0253), and the channel cannot open without them.
- One metric definition across agent, operator and customer MCP traffic.
- Per-party channel history exists for investigation, erasable by destroying one key, and gone
  after 90 days regardless.
- Approval-time fraud decisions know a payment started as an MCP proposal, without the channel
  becoming an exemption.

**Negative**
- mcp-service gains its first outbox: chained audit rows under a per-producer lock, which caps
  audited-call throughput (D0), plus plain telemetry rows. That is two database writes per customer
  tool call.
- A new topic, a new Vault key per party with an `erased_subjects` table, a new sink endpoint, a new
  suspension aggregate and a break-glass review record are all new design, not reuse.
- The route now also waits on the warehouse erasure backend being deployed (Context 7), and on the
  proposal store existing (ADR-0383 D6).
- A libs-fincrime contract change (`InstructionOrigin`), and the first `four_eyes` wiring in
  delegation-service, together with the exemptions it forces.
- The `client` label caps the allowlist at 25 before the live views must be redesigned.

**Neutral**
- Follow-up #16608: refresh-family revocation is a realm fact, so it stays invisible until a
  customers-realm event listener feeds the log pipeline. This is not a route prerequisite.

**Enforcement.**
- New checker `check-mcp-customer-observability.py`, gate `mcp-customer-channel-observable`
  (mode `planned`, enforced when the first customer-channel code lands): fails when any
  `openbank-infra` HTTPRoute/Ingress backs onto mcp-service while any of these is missing — the
  `channel` tag in `McpMetricsAdapter`, the D6 PrometheusRule names, the `mcp-customer-read`
  journey with `status: active`, the gold migrations, and the registry entries. It also fails on a
  forbidden label name (`party`, `party_ref`, `jkt`, `sub`, `grant`) in any `openbank.mcp.*` meter.
- New planned work in the SLO registry: a per-service SLO schema in `rules.yaml` and a
  `check-slo-registry.py` extension that validates the mcp-service Pyrra objects against it.
- Existing enforced gates that will hold parts of it: `scheduler liveness adoption` (D1
  projection), `journey-catalog-integrity` and `journey-money-path-accountability` (D6 journey),
  the ADR-0252 synthetic-taint outbox gate (D2 outbox), and `check-duplicate-yaml-keys.sh`.
- `docs/threat-models/openbank-mcp-service.md` and `openbank-delegation-service.md` gain the
  telemetry, the key scheme and the four operator actions.

### Delivery check

Every test named below is new unless marked *(exists)*, and each one must fail when its control is
removed. In the sandbox the route stays closed until all pass.

- **Durable audit (D0).** `./gradlew :openbank-mcp-service:test --tests '*McpAuditChainOutboxIT'`
  shows a `tools/call` commits one chained outbox row, and that removing the build property makes
  the test fail (the bean disappears). In the sandbox, after the journey, audit-service holds the
  journey's `mcp.tool.call` events with `channel` set, and its chain verify endpoint reports no
  broken link for producer `openbank-mcp-service`.
- **Client cap.** `McpClientAllowlistCapTest` shows a 26-entry allowlist fails to boot and a
  25-entry one boots. `McpMetricsAdapterTest` *(exists, extended)* drives a 25-client fixture plus
  one unknown `azp` and asserts exactly 26 `client` values (25 plus `unlisted`). With the mapping
  to `unlisted` removed, it asserts 26 distinct raw ids and fails.
- **Identical tag keys.** `McpMetricsAdapterTest` *(extended)* records one agent and one customer
  call into a `PrometheusMeterRegistry` and asserts the scrape contains both, with
  `client="none",synthetic="false"` on the agent series. Dropping the placeholders makes the
  registry reject the second registration, and the test fails.
- **No forbidden labels.** The `mcp-customer-channel-observable` gate's self-test feeds a fixture
  meter tagged `party_ref` and must fail. In the sandbox,
  `count({__name__=~"openbank_mcp_.*", party_ref=~".+"})` returns empty, confirmed against a
  deliberately mislabelled canary series in the gate's self-test run.
- **Every control carries work.** After the `mcp-customer-read` journey and its negative twin (no
  DPoP proof, replayed `jti`, a tokenless flood), `increase(openbank_mcp_customer_rejected_total
  {layer="dpop",reason="replay"}[15m]) > 0`, likewise for `reason="missing"` and
  `layer="throttle",reason="unauth_bucket"`, and `openbank_mcp_tool_calls_total{channel="customer",
  synthetic="true",result="SUCCESS"}` increased.
- **Synthetic appears where the taint includes it.** The journey's party is visible on the admin
  Connections view (marked synthetic), its approval produced a fraud-service decision record, and
  `SELECT count() FROM openbank_analytics.silver_mcp_tool_calls WHERE synthetic = 1` is > 0.
- **…and is absent where it excludes it.** The journey uses a canary-only allowlisted client.
  `SELECT sum(calls) FROM gold_mcp_channel_daily WHERE client = '<canary client>'` returns 0 while
  `openbank_mcp_tool_calls_total{channel="customer",client="<canary client>"}` is > 0. Removing
  `synthetic = 0` from the migration must make this check fail.
- **Retention.** `SELECT name, engine_full FROM system.tables WHERE name IN
  ('silver_mcp_tool_calls','gold_mcp_abuse_indicators')` shows `INTERVAL 90 DAY` on both.
- **Key derivation and erasure.** `PartyRefDerivationIT` in analytics-sink, against a dev OpenBao
  with Transit, runs these steps:
  0. A lookup for a never-seen party Q returns "no activity", and no key `analytics-party-<Q>`
     exists afterwards.
  1. An event for party P creates the key `analytics-party-<P>`.
  2. The stored `party_ref` equals a direct Transit HMAC under that key.
  3. An erasure request for P returns `ERASED`, not `NO_BACKEND`, and the key is gone.
  4. Recomputing the HMAC is impossible (404).
  5. A new event for P stores `party_ref = NULL`, and `erased_subjects` holds P.
  With the `erased_subjects` check removed, step 5 recreates a key and the test fails. With the
  lookup's derive-only guard removed, step 0 leaves a key behind and the test fails.
  `PartyActivityCallerGuardTest` shows that another service account, and the admin-ui account
  without a compliance-role operator token, both get 403. It also shows that each call writes one
  audit event.
- **No PII on the wire.** `McpToolCallEventSchemaTest` rejects any property named `iban`, `amount`,
  `reference`, `arguments`, `body` or `result` in the tool-call AsyncAPI schema.
- **Topic registry.** `openbank-libs-domain/.../analytics/TopicProducers.kt` lists
  `openbank.delegation.mcp-client-events` (producer delegation-service) and
  `openbank.mcp.customer.tool-calls` (producer mcp-service).
- **Topic separation.** A consumer test in each of account-service, card-issuance-service,
  notification-service, tax-reporting-service and context-service feeds an `MCP_CLIENT` record and
  asserts it is ignored. `grep -c MCP_CLIENT` over the `openbank.delegation.events` channel of the
  delegation AsyncAPI returns 0.
- **Governed actions.** `McpClientSuspensionTest` and `BreakGlassReviewTest` in delegation-service
  show that:
  - a single operator's suspend makes the next grant lookup for that `azp` deny, and opens an
    `OPEN` review;
  - the suspending actor cannot close the review;
  - an overdue review raises the gauge.

  `McpClientFourEyesIT` shows unsuspend and bulk revoke change nothing until a second operator
  approves, and that the maker cannot approve their own request. `DelegationRevokeExemptionIT`
  shows the edge service account's `delegation.revoke`, `delegation.disclosure.release` and
  `delegation.reserve.release` are each not parked once the `ApprovalStore` is wired.
  The persistence is new Flyway migrations in delegation-service (`mcp_client_suspension`,
  `breakglass_review`), and the four actions are new operations in delegation-service's
  `openapi.yaml`, classified by the ADR-0048 contract gate. Both ship with the tests above.
- **Fraud evidence.** With a shadow rule reading `origin`, an approved journey proposal yields a
  fraud decision whose recorded context has `initiationMode = CUSTOMER_PRESENT` and
  `origin = MCP_CLIENT_PROPOSAL`, and the shadow rule leaves the verdict unchanged.
- **Proposal funnel** (delivered with the ADR-0383 D6 store, and its status follows that store): a
  journey proposal rejected in the app increments `openbank_mcp_customer_proposals_total
  {stage="rejected"}`.
- **Order.** `kubectl get httproute,ingress -A | grep mcp` lists nothing until every line above
  holds, and the gate fails if one is added earlier.

## Compliance impact

- PCI DSS: not applicable — no tool, metric or warehouse fact on this channel carries cardholder data.
- DORA:    ICT risk and third-party monitoring engagement — external customer-chosen clients get per-client health, reject mix and a reviewed suspension action.
- GDPR:    data minimisation and storage limitation engagement — no party dimension in metrics, keyed shreddable refs in the warehouse, no argument or response content anywhere, 90-day retention for access facts.
- PSD2:    fraud monitoring engagement — MCP-originated payments are scored at approval with their origin as evidence and keep full SCA; no exemption is introduced.
- CNB:     not applicable — no reporting or supervisory artefact changes; synthetic activity stays out of every aggregate.

## References

- ADR-0003, ADR-0022, ADR-0023, ADR-0056, ADR-0077, ADR-0084, ADR-0088, ADR-0118, ADR-0139,
  ADR-0155, ADR-0171, ADR-0189, ADR-0226, ADR-0228, ADR-0237, ADR-0241, ADR-0252, ADR-0253,
  ADR-0286, ADR-0047, ADR-0303, ADR-0304, ADR-0323, ADR-0329, ADR-0354, ADR-0374, ADR-0377, ADR-0378;
  ADR-0383
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/observability/McpMetricsAdapter.kt`
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/application/McpCallAuditor.kt`
- `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/audit/AuditEventPublisher.kt`
- `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/audit/HashLinkedOutboxAuditEventPublisher.kt`
- `openbank-contracts/openbank-delegation-service/asyncapi.yaml`
- `openbank-analytics-sink/src/main/resources/clickhouse/V9__synthetic_provenance.sql`
- `openbank-analytics-sink/src/main/kotlin/com/openbank/analytics/infrastructure/erasure/VaultCryptoErasure.kt`
- `openbank-libs-fincrime/src/main/kotlin/com/openbank/libs/fincrime/FraudRiskContext.kt`
- `openbank-admin-ui/src/lib/reporting/registry.ts`
- `openbank-libs/governance/journeys.yaml`
- Issues #16590, #16605, #16608; PR #16599 (`DelegationGranteeType`)
