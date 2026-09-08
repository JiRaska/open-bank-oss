---
date: 2026-09-07
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance, ci, observability]
summary: "A scheduled lane compares what the repository declares with what the running system holds, because every existing gate compares one representation with another and none of them can see the cluster."
---

# ADR-0288 — Reality checks: a scheduled lane that compares the repository's claims with the running system

## Context

The gate estate is large and, within its own terms, effective. It is also uniform in one
respect that has never been stated: **every gate compares a representation with another
representation.** Git against a ConfigMap. An OpenAPI document against its previous
revision. A migration directory against a baseline file. Not one of them asks the running
system whether any of it is true, because the pull-request lane has no cluster access and
should not have one.

That is not a theoretical gap. Four instances, all measured on the sandbox on 2026-09-06
and 2026-09-07, all found by hand rather than by a check:

- **Five merged ClickHouse migrations had never been applied.** The deployed
  `clickhouse-init` ConfigMap carried all fourteen keys; `openbank_analytics` held the
  objects of V1–V6 only. The oldest missing migration (V9, synthetic provenance) had been
  on `main` for thirteen days, so ADR-0252's synthetic-fleet filtering was absent from
  every mart for that whole period and each aggregate silently mixed the bank's own probes
  into customer numbers. Both ClickHouse gates were green throughout, correctly: they
  compare the migration directory with the ConfigMap, and those two did agree.
- **The ADR-0213 decision evidence was never persisted.** Outcome, price band, reason
  codes, pinned policy versions and the input hash were computed, returned in the API
  response and emitted as an event — and the columns stayed NULL from
  `V11__decision_engine_inputs.sql` onward, because the transition claim wrote only status
  and the three human decision fields. No reader existed, so nothing disagreed.
- **44 of 45 `lending_outbox` rows carry `created_at` of 1970-01-01** and no `occurredAt`
  in the payload. This is #3272's defect (388 of ledger's 553 rows) recurring in a second
  service, with the same absence of a reader.
- **A remedy that could not work was merged and turned a gate green.** Two migrations
  claimed version 14 in `openbank-notification-service`; the gate's own message prescribed
  `QUARKUS_FLYWAY_OUT_OF_ORDER=true`, which resolves a *lower* version arriving late and
  cannot resolve an *equal* one. For a period `main` held a silenced gate over a service
  that would still refuse to boot — strictly worse than the red gate it replaced.

The common shape is not carelessness. In each case a person or a gate read an artifact,
found it correct, and inferred a fact about the running system that did not hold.

**ADR-0253 already names the principle.** It requires every control to expose a countable
artifact and to be covered by a reader that fails when the count is zero, and it says in
as many words that live-data inspection is a first-class verification step. It is
`proposed/partial`, and two things keep it from covering the cases above. It addresses
*controls* — a control that acts and leaves a trace — whereas four of the five findings
here are invariants of *data*: a row that should exist, a column that should not be NULL,
a timestamp that cannot precede the service. And it names no place to run: a quarterly
manual sweep is the only mechanism it offers, which is how these defects were in fact
found, and a mechanism that depends on somebody deciding to look is not a control.

## Decision

**We will run a scheduled verification lane whose checks compare a declaration in this
repository with the corresponding state of a running environment, and we will treat its
findings with the same weight as gate findings.**

**D1 — A reality check compares declared with observed, never declared with declared.**
Objects named by the ClickHouse init ConfigMap against `system.tables`; migration versions
in a service's `db/migration` against that service's `flyway_schema_history`; topics a
service's `application.yaml` subscribes to against the consumer group's actual
subscription; a deployed image tag resolved to the commit it was built from, against what
that commit contains. A check that can be satisfied without contacting the environment
belongs in the pull-request lane, not here.

**D2 — `UNKNOWN` is a first-class verdict and never counts as a pass.** An empty table and
a broken pipeline produce the same silence; a check that cannot distinguish them must say
so. Concretely: every check declares the population it is about, and an assertion over an
empty population reports `UNKNOWN`, never `PASS`. This is not hypothetical prudence — the
first version of the lending-subscription verification written for #8893 reported `PASS`
because both of its "must be zero" conditions were trivially satisfied by a population of
zero rows.

**D3 — Every check names the artifact it counts.** This is ADR-0253's first obligation,
restated here because it is also the test of whether a check can learn anything: a check
whose artifact is "no error in the log" has silence as both its healthy and its broken
state, and cannot be written as a reality check at all.

**D4 — Findings are routed, not merged into the PR verdict.** The lane runs against a
deployed environment, so its findings are about that environment and not about the change
under review; failing a pull request for them would punish an author for someone else's
deploy. A finding opens or updates an issue and, for the money-path subset, raises the
existing alert path. The graduation discipline of ADR-0144 applies unchanged: a new check
starts advisory and becomes blocking for the *lane* once its false-positive rate is known.

**D5 — The lane reads and never writes.** It holds read-only credentials. Remediation —
applying a migration, correcting a row — stays a human act with its own review, because a
lane that can repair what it measures can also hide what it measured.

**D6 — The first three checks are named here, so the ADR is falsifiable.** Migration
parity for ClickHouse (#7645), outbox timestamp sanity across every service that owns an
outbox (#9003), and subscription-to-ingestion continuity for analytics (#8893). Each of
the three corresponds to a defect measured above, which is the evidence that the lane
would have caught something rather than merely existing.

## Alternatives considered

- **Give the pull-request lane cluster access.** Rejected on two grounds. PR runners would
  need standing credentials to a production-adjacent environment, which is a trust
  boundary this estate has deliberately avoided; and it answers the wrong question anyway,
  because at PR time the change is not deployed, so "does the cluster match?" is not yet a
  meaningful question about it.
- **Rely on runtime alerting.** Prometheus and the alert set already watch the running
  system, and they are the right tool for its health. They cannot express divergence from
  the repository: nothing in a running ClickHouse knows that a fourteenth migration exists
  in git. The comparison needs both sides, and only CI holds one of them.
- **Keep the quarterly manual sweep ADR-0253 already proposes, and nothing more.**
  Rejected as the sole mechanism, not as an idea — it is how these findings surfaced. But
  it surfaced them because someone applied a migration by hand and hit an error, which is
  luck rather than cadence, and a sweep's result lives in whoever ran it.
- **Do nothing and rely on the deploy pipeline.** This is what was in place. The
  ClickHouse case shows its limit precisely: the deploy succeeded, the ConfigMap was
  correct, the pod restarted — and the schema was thirteen days stale, because
  first-boot initialisation is not a deployment mechanism and nothing said so.

## Consequences

**Positive**
- The dominant defect class in this estate — wired, healthy, silent — becomes detectable
  by a machine rather than by a person who happens to look.
- ADR-0253 gains the runtime it lacks, and extends from controls to data invariants.
- A gate's green stops implying a claim it was never able to make; the two kinds of
  assurance become separately visible.

**Negative**
- A scheduled job with read credentials to a live environment is a new trust boundary and
  needs its own threat-model entry.
- Environments legitimately diverge — a sandbox may sit behind `main` between deploys — so
  the first months will produce findings that are true and uninteresting. D4's advisory
  start exists for that, and a check that cannot express "behind, but expected" is not
  ready to graduate.
- One more lane to keep alive. A reality lane that silently stops running is exactly the
  failure it was built to detect, so it must count its own runs.

**Neutral**
- No change to the pull-request lane's runtime or cost.
- The capability already exists in the estate: five gate scripts shell out to `kubectl`
  today. What this ADR adds is the place to put such checks and the rules they follow.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is read; the lane inspects schema and
  metadata, not payment records.
- DORA:    ICT risk management. A schema or pipeline that is believed deployed and is not
  is an availability and data-integrity risk in a tier-classified service, and the
  evidence of divergence is exactly what an ICT-risk review asks for.
- GDPR:    not applicable in the lane's design — checks compare counts, names and
  timestamps. A check that would need to read personal data to answer its question must
  say so in review and is out of scope until then.
- PSD2:    not applicable.
- CNB:     indirect. Supervisory returns derive from the warehouse; a mart that is stale
  or missing produces a return that is wrong in a way nobody can see, which is the
  condition this lane exists to remove.

## References

- ADR-0253 — evidence of effect: the principle this ADR gives a runtime to.
- ADR-0022 — analytics layer: the ClickHouse warehouse whose apply gap produced the first finding.
- ADR-0144 — gate graduation: the advisory-to-enforced discipline D4 reuses unchanged.
- #7645 — ClickHouse has no idempotent migration mechanism; reopened 2026-09-06 with the live measurement.
- #9003 — 44 outbox rows stamped 1970, #3272's defect recurring in a second service.
- #8893 — the credit stream reaching the warehouse, and the verification that reports PARTIAL rather than PASS.
