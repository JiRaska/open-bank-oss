---
date: 2026-08-02
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [gitops, observability, ci]
summary: "Compare every deployed sandbox-<sha> image pin against version.txt on main daily; drift older than 7 days escalates onto one deduped issue per service. Declaration lane blocks malformed pins at merge."
---

# ADR-0236 — Deployed == main drift watch

## Context

The operational-maturity assessment (#3343) scored the platform at level 1: artifacts
exist, run evidence does not. Its sharpest finding was that the sandbox ran lending
0.11.5 while main was at 0.20.2 — *what runs is not what is tested* — and the drift
surfaced by accident, not by a check. Every other measurement (SLO, perf, alert)
targets whatever binary happens to be deployed, so undetected drift silently voids
them all.

Two facts make the drift cheaply checkable: every gitops component manifest pins an
immutable `sandbox-<sha>` image tag (deployed state is a *committed fact* in this
repo), and every released module carries `version.txt` (the release axis, ADR-0048).
"What is deployed" and "what is main" were both one command away; nothing compared
them daily. ADR-0160 established the same principle for integration liveness —
claims turned into CI-checked facts; this ADR extends it to the deploy axis.

## Decision

We will run a daily **deployed == main drift watch** with two deliberately different
lanes (the same shape as the external feed watch):

- **Declaration lane (blocking, enforced in `gates.yaml`).** Every ECR `openbank-*`
  image pin in `openbank-infra/gitops/components/**/*.yaml` on a module with a
  `version.txt` must be a `sandbox-<sha>` tag. Offline and deterministic; a hand-set
  or malformed tag (`latest`, `sandbox-sec1`) cannot merge, because it would make
  the scheduled lane blind for that service while the repo reports green.
- **Drift lane (scheduled, never blocks a merge).** Daily, resolve each deployed
  `sandbox-<sha>` against main's history and compare `<module>/version.txt` at that
  commit to `version.txt` on main. Version mismatch older than **7 days** is DRIFT;
  an unparseable tag, a sha not in the repo, or a sha that is not an ancestor of
  main is UNVERIFIABLE and reported at any age. Drift escalates onto **one open
  `deploy-drift` issue per service** — refreshed in place, auto-closed when the
  deployment catches up. The scanner fails closed: 0 images found or a git error
  exits 1, so a scan that read nothing can never report green, and a `--self-test`
  harness drives the flagging paths before any verdict is trusted.

The comparison contract is the **release axis** (version.txt), not the sha itself:
a pin behind main whose version matches has only non-releasing changes behind it
(rule #2 forces a bump for src changes), which is tolerated drift, not signal.

## Alternatives considered

- **Compare image tag semvers to version.txt directly.** Rejected: tags are
  `sandbox-<sha>`, not semver — there is no version string in the pin to compare.
  The sha indirection is also the only truthful provenance: it proves *which commit*
  was built, not just which version it claimed to be.
- **Query the cluster / ArgoCD for live state instead of committed manifests.**
  Rejected for this gate: the committed gitops tree is the desired-state source of
  truth, needs no cluster network access from CI (there is no public ingress,
  ADR-0056), and stays auditable in git. Live-state comparison is a complementary
  control-plane-monitoring item on the 2→3 path (#3343), not a substitute.
- **Block merges or deploys on drift.** Rejected: an undeployed service is an
  operational signal, not a defect in the PR at hand. A red scheduled gate is a red
  addressed to nobody — escalation onto a deduped issue is the channel that gets
  read (same reasoning as the external feed watch's liveness lane).

## Consequences

**Positive**
- Deployed == main became a daily-checked fact on day one: the first run found 6
  real drifts (issues #3362–#3367, the oldest 16 days behind main).
- Every other operational measurement (SLO, perf baseline, alert hygiene) regains a
  known binary to measure against — the precondition for the rest of the 1→2 path.
- The two-lane + dedupe-issue + auto-close pattern is now proven twice (this and
  the feed watch) and is reusable for the scheduler-heartbeat watch (#3345).

**Negative**
- A stalled auto-deploy produces standing issues; mitigated by the 7-day threshold
  and refresh-in-place (no daily spam), and by a watchdog that reads the watch.
- The watch itself is an artifact that must run — the exact defect class it exists
  to catch. Mitigated by fail-closed exit codes, self-test, and run-evidence review
  (template on #3343).

**Neutral**
- The 7-day threshold is a starting guess, tunable via the `DRIFT_DAYS` workflow
  env var without a code change.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data surface.
- DORA: operational resilience / change-management engagement in plain words — the
  watch measures deploy drift and produces the run evidence the maturity assessment
  found missing; no specific clause cited in this ADR.
- GDPR: not applicable — no personal-data path.
- PSD2: not applicable — no customer-facing API change.
- CNB: not applicable — no regulatory reporting change.

## References

- Issue #3343 (operational maturity tracker, level 1 → 2)
- Issue #3344 + PR #3358 (implementation), PR #3361 (label fix)
- Issues #3362–#3367 (first-run drift findings)
- ADR-0160 (end-to-end liveness and drift-detection standard)
- ADR-0048 (two version axes — the comparison contract here is the release axis)
- `.github/workflows/external-feed-watch.yml` (the two-lane pattern this copies)
