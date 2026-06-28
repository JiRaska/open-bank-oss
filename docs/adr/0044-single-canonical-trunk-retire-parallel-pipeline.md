# Single canonical trunk — retire the parallel `ci/per-service-pipeline` branch

Date: 2026-05-31
Status: Accepted (2026-06-14 — implemented: the parallel `ci/per-service-pipeline`
branch is retired and no `per-service-pipeline` workflow remains under
`.github/workflows/`; `main` is the single canonical trunk with the unchanged
`all-green` aggregate gate. The two latent CI bugs noted here — Postgres readiness
and under-build — were fixed in the consolidation.)
Delivery-Status: Shipped
Author(s): Jiri Raska

## Context

ADR-0029 (governance-as-code) names a **single canonical trunk** as a first
principle: versions, lineage, and the service catalogue are *derived from the code
on `main` and enforced in CI*. That principle only holds if there is exactly one
trunk to derive from.

For several weeks there were two. `ci/per-service-pipeline` started as a CI branch
and then quietly became a second trunk: backend breadth accumulated on it that never
reached `main` — the lending/credit bounded context and its phases, several new
services (`analytics-sink`, `statement-service`, `sdd-service`, `anacredit-service`),
the hexagonal `application/port/out/` interfaces that a stale unanchored `.gitignore`
rule had been swallowing on `main`, threat-models, ADRs 0019–0039, `*_it` integration
docs, and Flyway migrations. Meanwhile `main` kept moving independently: the
warm-reuse per-service CI execution model (#163, ADR-0040/0043), the admin-ui
dependency floor (Next 16 / React 19 / next-auth 5), OPA authz (#86, ADR-0034 Phase 2),
ledger partition lifecycle (#21), Docs-as-Service primitives (#99).

At reconcile time the two trunks were **143 commits / 977 files apart** (pipeline
ahead by 143, `main` ahead by 37, and `main` still advancing during the reconcile).
This is exactly the branch-chaos failure mode ADR-0029 was meant to prevent:
catalogue/version derivation is ambiguous, contributors don't know which trunk is
authoritative, and the two diverge faster than anyone reconciles them.

The decision recorded here is *not* which features win — almost every conflict had a
mechanical answer (one side was a strict superset of the other). The decision is the
**structural** one: collapse back to one trunk and keep it that way.

## Decision

**We merge the accumulated `ci/per-service-pipeline` work into `main`, declare `main`
the single canonical trunk (per ADR-0029), and delete the pipeline branch. No
long-lived parallel trunk is permitted again — divergent work lives on short-lived
PR branches that rebase onto `main`, never on a standing second trunk.**

The reconcile is delivered as one PR (#164) whose own full-fleet per-service CI run
*is* the validation gate. Conflict resolution followed fixed rules so the merge
carried no hidden product decision:

| Area | Resolved to | Why |
|---|---|---|
| `.github/workflows/*` | **main** | Keeps the warm-reuse per-service CI (ADR-0040/0043) — the authoritative execution model. |
| `settings.gradle.kts` | **union** | All pipeline service `include()`s **+** main's remote build-cache block. |
| `.gitignore` | **anchored `/out/`** | Stops the unanchored rule swallowing hexagonal `application/port/out/` source. |
| Backend breadth | **pipeline** | lending + phases, new services, port/out interfaces, threat-models, ADRs 0019–0039, `*_it` docs, migrations — pipeline is the superset. |
| admin-ui deps (`package.json`/lock) | **main** | Next 16 / React 19 / next-auth 5 is ahead of pipeline's Next 15 / next-auth 4. |
| product-catalog test + build | **pipeline** | Superset incl. the new `prod-014` CZK multi-currency test. |
| `DocsResource` (libs) | **pipeline** | `openbank.docs.v3` is a forward-compatible superset of main's `#99` v2 (adds `links`). |

Two robustness bugs surfaced while validating the reconcile and were **fixed forward**
inside the same PR rather than worked around, because both are latent on `main`
independent of the reconcile:

1. **Postgres readiness race in `_service-ci.yml`.** `pg_isready` over the Unix
   socket returns healthy during Postgres's socket-only init-bootstrap phase, so the
   per-job DB-reset step raced the real TCP listener and hit
   `the database system is shutting down`. Fixed to probe TCP (`-h 127.0.0.1`).
2. **Under-build hole in `services-ci.yml` change detection.** A `--depth=1` base
   refetch grafted a shallow boundary onto the base ref, breaking `merge-base` for the
   three-dot `BASE...HEAD` diff once `main` advanced past a PR's base — collapsing the
   changed-service set and letting PRs pass `all-green` **without building the services
   they changed** (a branch-protection hole). Fixed by refetching at full depth.

## Alternatives considered

- **Keep both trunks, reconcile periodically.** Rejected: this is the status quo that
  produced a 977-file gap. The trunks diverge faster than reconciliation closes them,
  and ADR-0029's derive-from-code catalogue has no single source to read.
- **Rebase `ci/per-service-pipeline` onto `main` and fast-forward `main`.** Rejected:
  143 commits of pipeline history rebased over 37 of main's would replay every
  historical conflict commit-by-commit for no benefit — the squash-style single
  reconcile merge captures the same end-state with one auditable diff and one CI gate.
- **Cherry-pick pipeline features onto `main` selectively.** Rejected: the breadth
  (services, ports, migrations, ADRs) is too entangled to cherry-pick safely, and
  partial adoption would leave `main` in a non-compiling intermediate state.
- **Make `ci/per-service-pipeline` the new `main`.** Rejected: `main` carries the
  newer admin-ui dependency floor, the OPA authz interceptor, and the authoritative CI
  execution model; demoting it would regress that work and break branch protection,
  which is pinned to `main` (ADR-0042).

## Consequences

### Positive
- One authoritative trunk again: ADR-0029 version/lineage/catalogue derivation has a
  single source of truth; contributors have one place to branch from and merge to.
- The hexagonal `port/out` source that `.gitignore` had been silently dropping on
  `main` is restored fleet-wide.
- Two latent CI bugs (Postgres readiness, under-build hole) are fixed on the canonical
  trunk, closing a real branch-protection gap.

### Negative
- One large merge commit (850 files) is harder to review than incremental PRs — the
  rule table and per-conflict superset rationale exist to make it auditable, and CI
  builds every changed module to prove correctness.
- Any unmerged feature work still living only on a `ci/per-service-pipeline`-derived
  local branch must rebase onto `main` after this lands.

### Neutral
- Branch protection and the `all-green` aggregate status check are unchanged; they now
  point at the one trunk they were always meant to guard.

## Compliance impact

- **DORA (operational resilience / change management):** a single auditable trunk with
  enforced branch protection is the controllable change-management substrate; two
  divergent trunks are an audit finding waiting to happen. Net positive.
- **PCI DSS / PSD2 / GDPR / CNB:** no runtime or data-path behaviour changes — this is
  a source-control topology and CI-correctness change. The under-build fix strengthens
  the assurance that money-path services are actually built and tested before merge.

## References

- ADR-0029 — governance-as-code (single canonical trunk, derive-from-code/enforce-in-CI)
- ADR-0040 — CI execution model and cost (persistent self-hosted runners)
- ADR-0042 — enforce branch protection on `main`
- ADR-0043 — CI performance model (warm-reuse)
- PR #164 — the reconcile merge this ADR records
