---
date: 2026-11-17
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, ai-agents, fraud, privacy-gdpr]
summary: "The customer MCP channel opens only once it is observable: bounded metrics on the existing MCP series, outbox-fed warehouse facts with keyed party refs, an admin page with four-eyes revoke, and approval-time fraud evidence."
---

# ADR-0384 — Customer MCP channel observability: bounded telemetry, admin view, warehouse history and abuse signals before the route opens

## Context

**What this ADR is for.** ADR-0383 (proposed, PR #16591, not yet on `main` on 2026-11-17) decides
how a customer admits a third-party MCP client: an `MCP_CLIENT` delegation grant, DPoP-bound
tokens, layered throttling, and proposals that execute only after SCA in the bank app. It decides
the controls. It does not decide how anyone sees whether the controls carry work, how the channel
performs, who holds access, or whether it is being abused. Issue #16605 (sub-task of #16590) asks
for that. This ADR decides it, and makes it a precondition of ADR-0383's "Exposure last" step.

**Adjacent decisions and why they do not cover this.**
- ADR-0077 / ADR-0087 / ADR-0088: the metrics, traces, logs, alerting and Pyrra SLO-as-code
  substrate. They say *how* to instrument; nothing about this channel's signals, labels or alerts.
- ADR-0226: every audit event carries `channel` (`ui|mcp|api`), `act_chain` and `session_id`. It is
  the per-action trail. It is not a metric, not a warehouse fact, and (see below) it does not reach
  a store for MCP today.
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
   `McpMetricsAdapter` emits `openbank_mcp_requests_total{method}`,
   `openbank_mcp_tool_calls_total{tool,decision,result}`,
   `openbank_mcp_tool_call_duration_seconds{tool}` and
   `openbank_mcp_caller_identity_total{source}`, all `service="mcp"`, with `tool` mapped to a bounded
   value and no agent id, consent id or argument as a tag. The issue's
   `openbank_mcp_customer_calls_total{tool,outcome}` would duplicate it. The repo wins: the
   existing series gain a `channel` label instead.
2. **The MCP audit event does not reach a store.** `McpCallAuditor` builds the ADR-0226 envelope
   (`channel = mcp`, `actChain`, `sessionId`, payload with argument *keys* only). The only
   `AuditEventPublisher` implementation on `main` is libs-runtime's `LoggingAuditEventPublisher`,
   and its log line carries `eventId, at, actor, actorType, op, resource, resourceId, result,
   traceId` — not `channel`, `actChain` or `payload`. So for MCP, ADR-0226 D1 is a type, not a
   trail. This ADR does not build the audit transport (that is ADR-0226/ADR-0323 work), and it
   refuses to treat telemetry as a substitute for it.
3. **mcp-service has no outbox and no Kafka.** Its tree holds `McpRateLimiter` and
   `UnwiredProposalPort`, a Postgres database `openbank_mcp`, and no `outgoing` channel. A per-call
   event "over the existing outbox" does not exist to reuse; it must be built (ADR-0003).
4. **delegation-service already publishes grant lifecycle, and nobody consumes it.**
   `openbank.delegation.events` carries `DelegationOffered`, `DelegationActivated`,
   `DelegationRevoked`, `DelegationExpired`, `DelegationSuspended`, `DelegationReinstated` and more,
   and `rules.yaml` records it as producer-only. analytics-sink's topic list does not include it.
   The issue's new event names (`connected`, `revoked`, `expired`) are these events with a grantee
   type; they are not new types.
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
   instruction came from is missing evidence, and adding it is a contract change (D5).
9. **Scraping and key reuse are not fraud-service decisions.** ADR-0377 D1 scopes fraud decisions
   to value movements. A read sweep moves no value. Those detections belong to the channel's own
   abuse analytics and alerts, with a human acting on them (D3, D4), not to a fraud verdict.
10. **Customer self-revoke is exempt from four-eyes, and must stay so; the operator actions are
    not built.** `rules.yaml` defers four-eyes for delegation-service because `delegation.revoke`
    is customer self-service through the edge M2M identity and the service wires no
    `ApprovalStore`. The `rules.yaml` pattern for this case (the SDD note) is that an operator path
    gets its own distinct action, and that action is gated.
11. **A zero-traffic alert on real traffic is a standing false alarm at launch.** ADR-0241's goal is
    zero standing criticals. The repo's liveness answer for a customer-visible capability is a
    journey in `openbank-libs/governance/journeys.yaml`, run by a CronJob and held by the enforced
    `journey-catalog-integrity` and `journey-money-path-accountability` gates (ADR-0252 phase 2).
12. **Pyrra SLOs are registry-checked for money-path services only.** `check-slo-registry.py` reads
    `rules.yaml: slo` and `pyrra-slo-money-path.yaml`. mcp-service is not in
    `money_path_services`, so its SLO needs its own governed entry, not a hand-edited CR.

**Deadline.** None regulatory. The binding order is ADR-0383's: no route to mcp-service until its
delivery checks hold. This ADR adds its own checks to that list.

## Decision

We will make the customer MCP channel observable before it is reachable, using the estate's
existing signal paths, and we will not open the ADR-0383 route until every delivery check below
passes in the sandbox.

### D1 — Live metrics: extend the existing MCP series; labels are closed sets

- The existing four MCP series gain `channel ∈ {agent, operator, customer}`, derived from the
  ADR-0383 D1 resolution path (`iss` + path), never from a token claim the client controls.
- Customer-channel series additionally carry `client`. This is allowed **only because** ADR-0383
  D1 admits allowlisted, bank-registered clients with dynamic registration off, so the value set is
  the allowlist. The value is the allowlist entry's short id from mcp-service config; anything else
  is `unlisted`. A unit test fails when the allowlist exceeds **25** entries, because at that size
  `client` stops being a safe label and per-client views must move to the warehouse.
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
    The stages after `created` are counted by whichever service owns the proposal store
    (ADR-0383 D6; today `UnwiredProposalPort`, so none exist).
  - `openbank_mcp_customer_replay_store_up` (0/1) — the fail-closed dependency, scraped, not inferred.
- Gauges from a delegation-service projection, not from MCP and never per party:
  `openbank_delegation_mcp_active_grants{client}` and `openbank_delegation_mcp_connected_clients`.
  They are computed by a `@Scheduled` count, which therefore registers
  `registerWorkflowLiveness("mcp-grant-projection", …)` (ADR-0237; the enforced
  `scheduler liveness adoption` gate already requires it).

### D2 — Facts at the source

- **Connection lifecycle:** delegation-service's existing `DelegationActivated / Revoked /
  Expired / Suspended / Reinstated` events, unchanged in type, carry for an `MCP_CLIENT` grant:
  `granteeType`, allowlisted client id, capabilities, `expiresAt`, a revocation `reasonCode` from a
  closed set (`customer, operator, client_kill_switch, expiry, family_revoked_reported`), and
  `jktRef`. `jktRef` is an HMAC of the thumbprint under a delegation-service key; the raw `jkt`
  stays only on the grant row (ADR-0383 D3 needs it there).
- **Tool calls:** mcp-service gains a transactional outbox (ADR-0003, ADR-0013, `dispatch-enabled:
  true`, SENT purge per ADR-0329) and publishes one `mcp.customer.tool-call.v1` fact per
  customer-channel `tools/call`, **after** the ADR-0383 D5 token step (tokenless floods are counted
  in D1, never written per request). Fields: event id, occurred-at, client id, party id (raw, on the
  authenticated in-cluster hop only), tool, capability, decision, outcome, reject layer/reason,
  latency, response bytes, page count, the requested window length in days, and the synthetic
  header. **Never** argument values, response bodies, IBANs, amounts or free text.
  - The topic follows the fleet's DLQ rule: explicit nested `dead-letter-queue: topic:`, a
    `KafkaTopic` CR, and Write/Read ACLs (CLAUDE.md, #5745).
  - This fact is **telemetry, not audit.** The ADR-0226 audit event stays the per-action record and
    is not replaced by it; Context 2 is tracked as a dependency, not solved here.
- **Proposals:** the ADR-0383 proposal store emits `proposal.created|approved|rejected|expired`
  with client id, party id, `createdAt`, `decidedAt`. No payee, amount or reference on this topic;
  those live on the proposal row and reach fraud-service through D5.

### D3 — Warehouse history

- analytics-sink adds `openbank.delegation.events`, the tool-call topic and the proposal topic to
  its explicit topic list with Read ACLs, and the producer-only note for `openbank.delegation.events`
  is removed in the same change.
- **Pseudonymisation at the sink, by the existing per-subject key.** The sink replaces party id with
  `party_ref = HMAC(per-subject Transit key, party_id)`, using the same per-subject key model as
  `VaultCryptoErasure` (ADR-0023 F6), cached per subject in the sink. Erasure destroys that key, so
  nobody can recompute a `party_ref` from a party id afterwards and existing rows become unlinkable
  without mutating them. Until `ANALYTICS_ERASURE_BACKEND=vault` is deployed (Context 7), these
  topics are **not** ingested; a `NO_BACKEND` sink must not start collecting party-keyed channel
  history.
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
  Gold rows aggregate across parties and carry no `party_ref`, so they fall outside the 90-day
  class and keep the warehouse default.
- **Abuse indicators** are measurements, not judgements (V15's posture), with thresholds as
  registry parameters: distinct `party_ref` per `jktRef` (> 1 means a key reused across parties);
  new grants per client per 10 minutes; read-sweep score per `party_ref` and day (pages × window
  days over the tool cost table); proposals created per `party_ref` and day against the ADR-0383
  D6 limits; reject-ratio by client.
  `gold_mcp_abuse_indicators` exposes `party_ref` only for rows above a threshold, and only to the
  fraud permission (D4).

### D4 — Admin UI: one page, three reads, two gated actions

A `Customer MCP` page under the existing `/delegations` area. Every request goes through the BFF
(ADR-0056).
- **Overview** — embedded Grafana dashboard-as-code over D1 (ADR-0286 exploration path): calls,
  rejects by layer, p95, SLO burn, replay-store up, active grants, proposal funnel. Real traffic by
  default, with a synthetic toggle.
- **Clients** — one row per allowlisted client from mcp-service config joined with
  `gold_mcp_client_scorecard` via the query registry (permission `delegations:view`).
- **Connections** — party lookup through the existing party search and
  `/api/delegations/effective-access/[partyId]` (ADR-0228 is not a dependency), showing the party's
  `MCP_CLIENT` grants and, from the warehouse, the last 90 days of that party's tool-call counts by
  tool and outcome. The lookup is audit-stamped as a staff read of customer data.
- **Abuse** — `gold_mcp_abuse_indicators` via the registry, permission `compliance:view`, with a
  link into the ADR-0304 fraud lens when a case exists.
- **Actions, both four-eyes (ADR-0155), both new distinct actions** (Context 10):
  `delegation.operatorRevoke` (one grant) and `delegation.mcpClientSuspend` (all grants of one
  client, and MCP stops admitting that `azp`). Each needs a reason code, lands in `rules.yaml:
  four_eyes.actions`, and delegation-service wires the shared `ApprovalStore` for exactly these two.
  Customer self-revoke keeps its current exemption. The faster lever for a whole-channel stop stays
  the GitOps one (removing the route), which already takes a reviewed PR.

### D5 — Fraud: evidence at approval, account-control events, shadow first

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
- **SLO:** availability and p95 latency of customer-channel read tools, as Pyrra objects in a
  governed `rules.yaml: slo` entry for mcp-service; `check-slo-registry.py` is extended to that
  entry rather than the CR being hand-edited. Synthetic traffic counts toward it (ADR-0252: the
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
  Rejected: the MCP audit event reaches no store today (Context 2), and audit retention (5 years)
  and immutability are wrong for high-volume access telemetry (90 days).
- **Tool-call facts in `bronze_events` like every other event.** Uniform ingestion. Rejected: bronze
  keeps 10 years as a floor, and access logs are a 90-day class under ADR-0118.
- **Score scraping and key reuse in fraud-service.** One risk engine. Rejected: ADR-0377 scopes
  fraud decisions to value movements, and a read has no admission step to attach a verdict to.
- **Single-operator kill switch for speed.** Faster in an incident. Rejected for the per-client
  switch: it revokes every customer's grant for that client and is itself an abuse vector. The
  whole-channel stop remains available through the reviewed GitOps route removal.
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
- mcp-service gains an outbox and a database write per customer tool call.
- The route now also waits on the warehouse erasure backend being deployed (Context 7), and on the
  proposal store existing (ADR-0383 D6).
- A libs-fincrime contract change (`InstructionOrigin`) and a `four_eyes` wiring in
  delegation-service, which today has none.
- The `client` label caps the allowlist at 25 before the live views must be redesigned.

**Neutral**
- The ADR-0226 audit transport gap for MCP (Context 2) is unchanged by this ADR and stays a
  dependency of the channel's repudiation story, tracked against ADR-0226/ADR-0323.

**Enforcement.**
- New checker `check-mcp-customer-observability.py`, gate `mcp-customer-channel-observable`
  (mode `planned`, enforced when the first customer-channel code lands): fails when any
  `openbank-infra` HTTPRoute/Ingress backs onto mcp-service while any of these is missing — the
  `channel` tag in `McpMetricsAdapter`, the D6 PrometheusRule names, the `mcp-customer-read`
  journey with `status: active`, the gold migrations, and the registry entries. It also fails on a
  forbidden label name (`party`, `party_ref`, `jkt`, `sub`, `grant`) in any `openbank.mcp.*` meter.
- Existing enforced gates that will hold parts of it: `scheduler liveness adoption` (D1
  projection), `journey-catalog-integrity` and `journey-money-path-accountability` (D6 journey),
  the ADR-0252 synthetic-taint outbox gate (D2 outbox), and `check-duplicate-yaml-keys.sh`.
- `docs/threat-models/openbank-mcp-service.md` and `openbank-delegation-service.md` gain the
  telemetry and the two operator actions.

### Delivery check

Run in the sandbox, in this order; the route stays closed until all pass.

- **Labels are closed.** `count by (__name__)({__name__=~"openbank_mcp_.*", party=~".+"})` and the
  same for `party_ref`, `jkt`, `sub` return no series; `count(count by (client)
  (openbank_mcp_tool_calls_total{channel="customer"}))` ≤ the allowlist size + 1.
- **Every control carries work.** After the `mcp-customer-read` journey and its negative twin (no
  DPoP proof; replayed `jti`; flood without a token), `openbank_mcp_customer_rejected_total` has
  non-zero increments for `layer="dpop",reason="replay"`, `layer="dpop",reason="missing"` and
  `layer="throttle",reason="unauth_bucket"`, and `openbank_mcp_tool_calls_total{channel="customer",
  synthetic="true",result="SUCCESS"}` increased.
- **Synthetic appears where the taint includes it.** The journey's party is visible on the admin
  Connections view (marked synthetic), its approval produced a fraud-service decision record, and
  `SELECT count() FROM openbank_analytics.bronze_events WHERE synthetic = 1 AND aggregate_id =
  '<journey grant id>'` and `SELECT count() FROM silver_mcp_tool_calls WHERE synthetic = 1` are > 0.
- **…and is absent where it excludes it.** The journey uses an allowlisted client id that only
  canaries use. `SELECT sum(calls) FROM gold_mcp_channel_daily WHERE client = '<canary client>'`
  returns 0 while `openbank_mcp_tool_calls_total{channel="customer",client="<canary client>"}` is
  > 0; removing `synthetic = 0` from the migration must make this check fail.
- **Retention.** `SELECT engine_full FROM system.tables WHERE name = 'silver_mcp_tool_calls'`
  contains `INTERVAL 90 DAY`.
- **Erasure.** An erasure request for a test party returns `ERASED`, not `NO_BACKEND`, and the sink
  then cannot reproduce that party's `party_ref`.
- **No PII on the wire.** A consumer test over the tool-call topic schema rejects any property
  named `iban`, `amount`, `reference`, `arguments`, `body` or `result`.
- **Four-eyes.** `delegation.operatorRevoke` and `delegation.mcpClientSuspend` appear under
  `rules.yaml: four_eyes.actions`; an IT shows the maker cannot approve their own request and the
  grant stays active until a second operator approves.
- **Fraud evidence.** With a shadow rule reading `origin`, an approved journey proposal yields a
  fraud decision whose recorded context has `origin = MCP_CLIENT_PROPOSAL`, and the verdict is
  unchanged by the shadow rule.
- **Order.** `kubectl get httproute,ingress -A | grep mcp` lists nothing until every line above
  holds; the gate fails if one is added earlier.

## Compliance impact

- PCI DSS: not applicable — no tool, metric or warehouse fact on this channel carries cardholder data.
- DORA:    ICT risk and third-party monitoring engagement — external customer-chosen clients get per-client health, reject mix and a reviewed suspension action.
- GDPR:    data minimisation and storage limitation engagement — no party dimension in metrics, keyed shreddable refs in the warehouse, no argument or response content anywhere, 90-day retention for access facts.
- PSD2:    fraud monitoring engagement — MCP-originated payments are scored at approval with their origin as evidence and keep full SCA; no exemption is introduced.
- CNB:     not applicable — no reporting or supervisory artefact changes; synthetic activity stays out of every aggregate.

## References

- ADR-0003, ADR-0022, ADR-0023, ADR-0056, ADR-0077, ADR-0084, ADR-0088, ADR-0118, ADR-0139,
  ADR-0155, ADR-0171, ADR-0189, ADR-0226, ADR-0228, ADR-0237, ADR-0241, ADR-0252, ADR-0253,
  ADR-0286, ADR-0303, ADR-0304, ADR-0323, ADR-0329, ADR-0354, ADR-0374, ADR-0377, ADR-0378;
  ADR-0383 (proposed, PR #16591)
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/observability/McpMetricsAdapter.kt`
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/application/McpCallAuditor.kt`
- `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/audit/AuditEventPublisher.kt`
- `openbank-analytics-sink/src/main/resources/clickhouse/V9__synthetic_provenance.sql`
- `openbank-analytics-sink/src/main/kotlin/com/openbank/analytics/infrastructure/erasure/VaultCryptoErasure.kt`
- `openbank-libs-fincrime/src/main/kotlin/com/openbank/libs/fincrime/FraudRiskContext.kt`
- `openbank-admin-ui/src/lib/reporting/registry.ts`
- `openbank-libs/governance/journeys.yaml`
- Issues #16590, #16605
