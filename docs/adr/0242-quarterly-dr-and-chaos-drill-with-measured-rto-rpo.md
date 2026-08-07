---
date: 2026-08-04
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, governance, ci]
summary: "Move from 'runbooks exist' to 'recovery is measured' by holding a quarterly DR drill and a monthly chaos drill, each producing a published RTO/RPO or SLO-impact report in the ICT register."
---

# ADR-0242 — Quarterly DR and chaos drill with measured RTO/RPO

## Context

The platform already documents how to recover (ADR-0186): CNPG WAL archiving,
GitOps-reconstructable config, tested restore runbooks. It also documents how to
run infrastructure failure injections (ADR-0151): Chaos Mesh, sandbox-only,
hypothesis + abort condition, monthly rotation. What is missing is a **regular,
measured drill cadence** that produces defensible evidence.

ADR-0186 explicitly says today's T0 RTO 4 h / RPO 15 min are aspirational until
M6. That honesty is good, but without actual drills the numbers are not even
aspirational — they are unvalidated. The DORA ICT testing programme (Art. 24–26)
requires testing *at least annually* and *evidenced*; the BCP policy says the
same. The last DR restore test was 2026-05-12 and the last chaos experiment was
2026-04-28 — both sandbox-only, both unmeasured (no wall-clock RTO was captured,
only "restored successfully"). The operational-maturity assessment (#3343)
scored DR/chaos at level 1 for exactly this gap: capability exists, discipline
does not.

This ADR turns the existing capabilities into a governed cadence with two
deliverables per drill:

1. A measured result (RTO/RPO for DR, SLO-impact seconds for chaos).
2. An entry in the ICT register with follow-up issues for any target miss.

It does not introduce new technology; it uses Chaos Mesh (ADR-0151), CNPG
restore (ADR-0186), the existing runbooks, and GitHub Issues.

## Decision

1. **Quarterly DR drill, wall-clock measured.** Every quarter, on the first
   Tuesday of the month at 06:00 UTC, the platform team executes a declared DR
   scenario against the sandbox:
   - CNPG point-in-time restore of one money-path database to a known
     recovery target (e.g. "restore to 04:00 UTC today").
   - Full cluster rebuild from GitOps in a fresh namespace, validating that
     ArgoCD can reconstruct all money-path services without hand-edits.
   - The drill records:
     - **Declared RPO** = the time between the chosen recovery target and the
       actual restore completion.
     - **Measured RTO** = wall-clock from "drill declared" to "first healthy
       `/health/live` response from the restored service(s)". `openbank-libs`
       `/api/v1/info` health endpoint is the probe.
   - Result is posted as a Markdown report in `docs/bcp/dr-test-log.md` and a
     summary comment on the tracking issue for that quarter (#3347 is the
     parent epic; each drill gets a new sub-issue).

2. **Monthly chaos drill, SLO-measured.** On the third Monday of every month at
   08:00 UTC, a sandbox Chaos Mesh experiment runs from a declared YAML schedule
   in `openbank-infra/gitops/components/experiments/`. Each experiment:
   - Targets one failure class in rotation: CNPG primary failover, Kafka broker
     loss, node drain, network delay on the Temporal frontend, pod-kill on a
     money-path service.
   - Uses ADR-0151's hypothesis/abort-condition format.
   - Records the **measured impact on Pyrra SLOs** (available/error budget burn
     for the affected service(s)) during and after the experiment window.
   - Stores the result as a Markdown report in `docs/bcp/chaos-test-log.md`.

3. **Drill failures create follow-up issues within 48 hours.** A miss against the
   aspirational target (RTO > 4 h or RPO > 15 min for DR; SLO error-budget burn
   > 10 % during a chaos window) must have a tracking issue opened before the
   next weekly operational review. The issue links the drill report and cites
   the relevant ADR (this one or ADR-0151/0186).

4. **Automation: GitHub Actions scheduling + manual trigger.** Two workflows:
   - `dr-drill.yml` (quarterly, manual-dispatch enabled) runs preflight checks
     (cloud credentials, S3 WAL listing), executes the restore runbook steps as
     scripted shell jobs against sandbox, probes endpoints, and appends the
     report.
   - `chaos-drill.yml` (monthly, manual-dispatch enabled) applies the selected
     Chaos Mesh `NetworkChaos`/`PodChaos`/`StressChaos` manifest, waits for the
     declared duration, plots SLO impact from Prometheus, and appends the
     report.
   The workflows do not replace human judgement — a human must still approve the
   start in Slack for safety — but they automate measurement and report
   formatting.

5. **Scope: sandbox only.** Production drills are still out of scope, matching
   ADR-0151 and ADR-0186. The evidence is from a production-faithful sandbox,
   not production itself, and is labelled as such in the register.

6. **Starting state: Q3 2026 drill dry-run by 2026-08-31.** Because #3343 is a
   level-1→2 push, the first measured dry-run must happen before the end of
   August 2026 to validate the workflow before the scheduled Q4 drill.

## Alternatives considered

- **Wait for M6 multi-region DR and then start measuring.** Rejected: M6 has no
  committed date, and DORA requires *annual* testing regardless of target state.
  Measuring against the existing single-region architecture is both honest and
  sufficient until M6 changes the architecture.
- **Make the DR drill fully automated failover.** Rejected: the current
  single-region architecture has no failover target to promote; automating a
  rebuild still ends with human-executed PITR steps. The workflow automates
  measurement and reporting, not the entire recovery.
- **Monthly DR + quarterly chaos instead.** Rejected: DR restore is expensive
  (hours of wall-clock, human attention, cloud cost for a parallel rebuild);
  quarterly is proportionate to the architecture. Chaos experiments are
  lightweight enough for monthly.
- **Skip written reports and just keep CI logs.** Rejected: CI logs are not an
  auditable ICT register entry and do not last as long as the BCP policy
  requires (three years).

## Consequences

**Positive**
- RTO/RPO are no longer aspirational guesses; they are measured and trended.
- DORA Art. 24–26 evidence is produced on a schedule rather than assembled for
  an audit.
- Drill reports become the data that justifies M6 investment or identifies
  cheaper fixes before committing to multi-region.
- Repeated misses become explicit engineering backlog items instead of
  invisible operational folklore.

**Negative**
- Quarterly DR drills consume team time and cloud budget (parallel namespace,
  S3 egress for WAL replay).
- Public reports in `docs/bcp/` must avoid real transaction content and use
  anonymised identifiers.
- If leadership pressures to "pass" the targets, the drill can be gamed
  (smaller databases, ideal conditions). This requires strong review discipline.

**Neutral**
- Does not change the single-region architecture decision (ADR-0186) or the
  chaos tooling choice (ADR-0151); it governs how those capabilities are used.
- The BCP policy remains the owner of tier definitions and crisis communication;
  this ADR owns the drill cadence and measurement method.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data surface.
- DORA: Art. 24–26 (digital operational resilience testing programme) — this
  ADR enacts the cadence and evidence recording that ADR-0151 and ADR-0146
  referenced.
- GDPR: drill reports must not contain PII; identifiers are service-level.
- PSD2: not applicable.
- CNB: aligns with supervisory expectation of demonstrated, evidenced
  operational-resilience testing.

## References

- Issue #3343 (operational maturity tracker), #3347 (this item)
- ADR-0151 (chaos engineering policy), ADR-0186 (single-region DR posture)
- ADR-0146 (incident-response / ICT register), ADR-0098 (progressive delivery)
- `docs/bcp/bcp-policy.md`, `docs/bcp/dr-test-log.md`, `docs/bcp/chaos-test-log.md`
- `openbank-infra/gitops/components/experiments/` (planned Chaos Mesh manifests)
- `docs/runbooks/` (recovery runbooks)
