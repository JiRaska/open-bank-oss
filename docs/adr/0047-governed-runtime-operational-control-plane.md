---
date: 2026-05-30
decision-status: accepted
delivery-status: partial
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance, security-ops, audit, compliance]
summary: "A governed runtime control plane is limited to break-glass resilience controls and business-policy parameters; everything else stays in GitOps, and actions are declarative desired-state records with four-eyes, not per-pod RPC."
---

# Governed runtime operational control plane

**Delivery note (updated 2026-06-30):**
- **Governance primitives** — ✅ Shipped: `MakerChecker` state machine, `Proposal<T>` four-eyes logic, `AuditEvent` infrastructure live in `openbank-libs` and tested; readiness-probe drain and scheduler pause/resume operational knobs scoped.
- **Integration surface** — ⬜ Pending: OPA policy gate integration, admin-UI operational surface, and Tier-A break-glass approval queue not yet wired.

## Context

Quarkus/SmallRye makes it trivial to change a running service's behaviour without a
redeploy: `CircuitBreakerMaintenance` can reset a breaker, the `Scheduler` API can
`pause`/`resume` a `@Scheduled` job, log levels are runtime-mutable, and a `@Readiness`
probe can be flipped to drain a pod. The temptation is to expose these in the admin UI
as "operational knobs".

In a regulated bank this is the wrong default. **Any change to the behaviour of a
running system is a controlled change** (DORA Art. 16 change management, EBA ICT &
security risk-management GL, ISO 27001 A.12). For parameters that affect customer
outcomes or the risk profile (limits, fees, AML/sanctions thresholds) it is stricter
still: segregation of duties, reversibility, effective-dating and an immutable trail are
mandatory (EBA/GL/2020/06, BCBS 239, PSD2 RTS for SCA limits, AMLD for screening
thresholds).

We already have GitOps (ADR-0010, ArgoCD) as the primary change path. GitOps **already
provides** four-eyes (PR review), an immutable audit trail (git history), effective-dating
(commit/sync time) and rollback (revert). So a runtime control plane is not free — it
adds an attack surface and a second governance path that must justify its own existence.

We also already have most of the governance primitives:

- `MakerChecker` / `Proposal<T>` four-eyes state machine, with the proposer≠checker rule
  enforced *in code* — `openbank-libs/src/main/kotlin/com/openbank/libs/analytics/MakerChecker.kt`
  (introduced for analytics reloads, ADR-0023; this ADR generalises it into `libs/governance`).
- The canonical audit envelope `AuditEvent`
  (`openbank-libs/.../audit/AuditEvent.kt`), GDPR Art. 30 / DORA Art. 17 mapped.
  Producers construct and publish it explicitly. This line once also claimed an
  `@Audited` annotation; it had no interceptor in libs or in any service and was
  never applied anywhere, so it was removed rather than counted (#4011).
- A **read-only** live config endpoint `ServiceConfigResource` (`/api/v1/config`) and the
  admin UI `/system/config` page that today says "change YAML and redeploy".
- An automatic compliance kill-switch in `openbank-infra/bcp-health-check.sh` that halts
  payments when AML/Sanctions/Balance degrade (DORA Art. 25) — but it is not
  operator-controllable.
- OPA fine-grained authz (ADR-0018) and the AI-agent governance model, where an
  agent may *propose* but never *approve* or *execute*.

The question this ADR settles: **when, and how, may behaviour be changed at runtime such
that it is provably correct to a CNB/EBA examiner — and when must it stay in GitOps?**

## Decision

We will build a **governed runtime operational control plane**, deliberately narrowed to
the only two cases that GitOps cannot serve well, and declarative by construction.

**Scope — runtime is justified for exactly two cases; everything else stays in GitOps:**

- **(A) Break-glass operational-resilience controls** — payment-rail halt per corridor,
  circuit-breaker reset, pause/resume of a scheduled job, log-level boost, pod drain.
  Justification: when money is leaking, redeploy latency (minutes) is unacceptable.
- **(B) Business-policy parameters owned by non-engineer roles** — transaction/SCA limits,
  fee/FX margins, AML/fraud/sanction-match thresholds, lending PD/LGD floors and IFRS 9
  staging thresholds. Justification: compliance/risk officers tune these at a cadence and
  with a persona that does not fit a git PR + deploy pipeline.

Infra config, timeouts, and engineer-owned feature rollout **stay in GitOps**.

**The control plane is declarative desired-state, not imperative RPC.** A control action is
a *record* in a per-service store, not a fire-once call to one pod. Every instance of a
service converges on that store (consumes a Kafka `ops-control` topic and/or polls), so
behaviour is identical across all N replicas. This is the central correction over the
naive design: `CircuitBreakerMaintenance.reset`, `Scheduler.pause`, log-level and readiness
are all **per-JVM**, so a button that hits one pod through the load balancer would silently
mislead the operator.

**Mechanism:**

1. Generalise `MakerChecker`/`Proposal<T>` out of `libs/analytics` into `libs/governance`
   (it is not analytics-specific). Payload `T` is the typed control action.
2. Each service owns a `PolicyStore` (Panache repo in *its own* Postgres — ADR-0009/0019,
   no central policy authority, no cross-service shared parameter). A typed
   `OperationalPolicy` accessor reads current values with a short-TTL cache, invalidated by
   a Kafka control event. **We do not use the SmallRye `ConfigSource` SPI** — `@ConfigProperty`
   resolves and caches at injection, so a ConfigSource nobody reads per-call would be dead
   weight.
3. **A curated, declared registry of governed parameters.** Each parameter is typed, bounded
   (min/max/enum), classified (Tier A/B), and given an owning role. Not "every YAML key is a
   knob" — the registry is code, CI-checked, UI-rendered (the ADR-0029 "derive → enforce →
   show" principle applied to runtime knobs).
4. **Version-pinned reads on the money path.** A business operation snapshots the resolved
   `policyVersion` at its start, records it with the outcome and in the `AuditEvent`, and
   never re-reads the parameter mid-flight. This preserves determinism/idempotency and
   answers "which policy decided this transaction".
5. **Effective-dating + reversibility.** A change is an append-only versioned record with
   `effectiveFrom`; rollback is proposing the inverse, never a destructive overwrite. The
   store answers point-in-time queries ("what was the sanction threshold on 2026-03-15 and
   who approved it") — same shape as the analytics SCD2/`asOf` projections (ADR-0023).
6. **Governance asymmetry.** Tightening / halting (fail-safe direction) is single-actor
   break-glass; loosening / resuming (risk-increasing direction) requires four-eyes.
7. **Authorisation and guardrails.** Backend `@RolesAllowed` (not UI-only gating):
   `ROLE_SUPERVISOR` for limits, `ROLE_COMPLIANCE` for AML/sanctions thresholds, `ROLE_SRE`
   for Tier A. The checker must come from a *different segregation domain* than the maker.
   The operator action itself requires step-up auth (WebAuthn). OPA (ADR-0018) gates which
   parameters are *proposable* and marks some changes *prohibited* outright (e.g. disabling
   sanctions screening can never be a valid proposal).
8. Every lifecycle transition (`PROPOSED`→`APPROVED`→`EXECUTED`, reject, withdraw) emits an
   `AuditEvent` with actor, before/after diff, `decisionReason` and `traceId`. Tier-A
   break-glass actions are additionally recorded as DORA-reportable operational events with a
   mandatory, time-boxed (e.g. 24h) post-hoc review by an independent function.

Implementation primitives are live on main: Temporal
durable workflows (ADR-0101) cover the break-glass pattern; OPA unified authz (ADR-0034)
covers policy-triggered runtime changes; MakerChecker four-eyes gate lives in
openbank-libs/src/main/kotlin/com/openbank/libs/governance/.

**Admin UI:** two pages — an *Incident Console* (Tier A break-glass, with the deferred-review
banner) and *Governed Parameters* (Tier B propose→approve→effective-date), both reading the
existing `/api/v1/config` extended from read-only to propose-change. AI agents land
in the same approval queue as makers only.

**Rollout:** start with Tier A on one non-money-path service to prove the audit path, then
Tier B parameters. Money-path services last (2 approvals + threat model, ADR-0030).

## Boundary with ADR-0067 (feature flags)

Both this ADR and ADR-0067 change runtime behaviour without a redeploy; they are
deliberately **different systems with different owners**, and must not be collapsed:

- **Feature flags (ADR-0067)** — engineer/product-owned **code-path toggles**: dark
  launches, progressive rollout, A/B experiments. Source of truth is **flag-as-code in
  git (GitOps)**, evaluated by a flagd sidecar; the only runtime overlay is a fail-safe
  **kill-switch** (can only disable, never loosen).
- **This control plane (ADR-0047)** — risk/compliance/SRE-owned: **Tier A break-glass**
  operational-resilience controls and **Tier B effective-dated business parameters**
  (limits, fees, AML/sanctions thresholds), via propose → four-eyes approve →
  effective-date in a per-service `PolicyStore`.

**Tie-breaker rule:** anything that **weakens a risk control** — e.g. disabling or
relaxing sanctions/AML screening, raising an SCA limit, bypassing four-eyes — is
**never a feature flag**. It belongs to this ADR's governance (four-eyes, effective-dating,
OPA prohibitions; disabling sanctions screening is *prohibited outright*, not merely
gated). Conversely, engineer-owned feature rollout never enters the `PolicyStore`.
For the grey zone: money-path flag flips on the ADR-0067 side still require four-eyes
(`libs/foureyes`, tracked in issue JiRaska/open-bank#419) — but if a "flag" would change a
risk/compliance-owned parameter or its threshold, it is a Tier-B parameter here, not a flag.

## Alternatives considered

- **Expose Quarkus runtime knobs directly (`CircuitBreakerMaintenance`, `Scheduler.pause`,
  log-level) as admin buttons.** Rejected: these are per-JVM, so with N replicas a single
  call is incorrect and misleading; and a raw knob has no four-eyes, effective-dating or
  audit. The declarative desired-state store fixes both.
- **SmallRye `ConfigSource` SPI backed by Postgres/Kafka.** Rejected: `@ConfigProperty`
  caches at injection, so injected values would not hot-update; a ConfigSource read only via
  explicit `config.getValue()` per-call is cargo-cult. A plain `PolicyStore` + typed accessor
  is simpler and honest.
- **Everything through GitOps (ADR-0010), no runtime plane at all.** Rejected only for the two
  scoped cases: redeploy latency is unacceptable for break-glass, and a git PR + deploy is the
  wrong workflow for a compliance officer tuning a threshold. GitOps remains the default for
  everything else — this ADR does not widen it.
- **Central bank-wide policy service.** Rejected: violates postgres-per-service (ADR-0009) and
  the "state in the service, not an external cluster" principle (ADR-0045), and creates a
  single point of failure for the whole estate. Parameters are service-local; cross-cutting
  ones stay in GitOps.
- **Single-actor for all runtime changes with audit-only.** Rejected: audit-after-the-fact is
  not segregation of duties; EBA/GL/2020/06 and BCBS 239 require a second control *before*
  risk-increasing changes take effect.

## Consequences

**Positive**

- Break-glass incident response in seconds, consistent across all replicas, fully audited.
- Compliance/risk can tune their own parameters without engineering or a deploy, inside a
  four-eyes + effective-dated envelope an examiner can reconstruct.
- Reuses existing primitives (`MakerChecker`, `AuditEvent`, `/api/v1/config`, OPA) — wiring,
  not greenfield.
- Point-in-time "which policy, who approved, when" answerable for any past transaction.

**Negative**

- New attack surface: an operator-controllable payment halt is a high-value target. Mitigated
  by step-up auth, OPA prohibitions and the desired-state store being the only mutation path.
- A second change-management path alongside GitOps — risk of scope creep. Mitigated by the
  hard two-case scope and a CI-checked parameter registry.
- Version-pinning on the money path adds a snapshot field to operations/sagas.
- Rubber-stamp risk on the approval queue; mitigated by cross-domain checker + independent
  post-hoc review, not eliminated.

**Neutral**

- The control plane is per-service; there is no global "halt everything" — by design.
- Tier-A break-glass trades a synchronous second approver for a deferred, time-boxed one.

## Compliance impact

- PCI DSS: not directly applicable (no CHD); operator step-up auth aligns with Req. 8.
- DORA: Art. 11–12 (operational resilience, break-glass), Art. 16 (change management),
  Art. 17 (incident records — every transition + halt is reconstructible), Art. 25 (the
  payment kill-switch this operationalises).
- GDPR: Art. 30 (the `AuditEvent` trail); payload diffs use `PiiMask`, never raw PII.
- PSD2: RTS for SCA — SCA-limit changes are Tier B, four-eyes + effective-dated.
- CNB: change to a prudential/conduct parameter is provably segregated, reversible and
  point-in-time reconstructible; sanctions/AML threshold changes are role-gated to compliance
  and cannot disable screening (OPA prohibition).
- EBA: GL/2020/06 (ICT & security risk mgmt — segregation of duties, change control); BCBS 239
  (risk-data governance — versioned, auditable parameter changes).
