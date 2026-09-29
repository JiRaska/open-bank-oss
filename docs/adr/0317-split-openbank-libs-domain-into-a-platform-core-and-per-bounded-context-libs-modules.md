---
date: 2026-09-26
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, architecture, ci]
summary: "openbank-libs-domain keeps only platform primitives; business packages (lending, iso20022, llm, analytics, cards/case/payment) move to per-bounded-context libs modules, cutting a business-package change from 57 rebuilds to 1-11."
---

# ADR-0317 — Split openbank-libs-domain into a platform core and per-bounded-context libs modules

## Context

ADR-0122 split `openbank-libs` along the **framework** axis (domain = framework-free, runtime =
Quarkus) to cut fleet rebuild cost. It explicitly rejected "one module per package" as
over-fragmentation; it did not consider the **bounded-context** axis, which is the one this ADR
decides. ADR-0014/0049 frame libs as a *shared service-infrastructure layer*; ADR-0028 put
"pure lending math in openbank-libs" and ADR-0139 put the ML feature store there, which is how
business code accreted in a module whose job is infrastructure.

Measured on `origin/main` 2026-09-26 (commands in *Delivery check*):

- `openbank-libs-domain` is ~10.4k LOC of `src/main` and is declared `implementation`/`api` by
  **57** modules; `.github/scripts/libs-change-dependents.sh` therefore rebuilds 57 services for
  any change under `openbank-libs-domain/src/main`. Via `openbank-libs-runtime`'s
  `api(project(":openbank-libs-domain"))`, Gradle's transitive reach is **68** modules.
- Consumers per package (distinct non-libs modules importing `com.openbank.libs.<pkg>.`, main+test):

| Package | LOC | Consumers | Kind |
|---|---:|---:|---|
| domain/identifiers | 215 | 61 | platform |
| observability | 303 | 53 | platform |
| authz | 125 | 48 | platform |
| persistence | 717 | 40 | platform |
| security | 236 | 21 | platform |
| approval | 77 | 19 | platform |
| api | 101 | 13 | platform |
| domain/event | 108 | 13 | platform |
| idempotency | 29 | 10 | platform |
| domain/money | 93 | 10 | platform |
| audit | 81 | 9 | platform |
| domain/calendar | 278 | 8 | platform (business calendar) |
| domain/account | 129 | 4 | platform (IBAN/account refs) |
| storage, flags, foureyes, governance, util, docs | 48-297 | 0-3 | platform |
| **llm** (+ reasoning 159 LOC, 0 direct) | 429 | 11 | agent |
| **iso20022** | 837 | 5 | payments |
| **analytics** (+ synthetic 103 LOC, 3) | 889 | 3 | analytics |
| **lending** (+ compliance 550, origination 506) | 2001 | 3 | lending |
| **decision** | 316 | 1 (lending-service) | lending |
| **product** | 256 | 3 | product |
| **identity** | 184 | 3 | onboarding |
| **contact** | 19 | 3 | engagement |
| **domain/feature** | 650 | 1 (fraud-service) | ml |
| **domain/case** | 272 | 1 (pid-service) | case |
| **domain/payment** | 127 | 1 (transaction-service) | payments |
| **domain/cards** | 250 | 0 | cards |

- Change frequency since ADR-0122 (`git log --since=2026-06-28T00:00:00Z` over the package path):
  67 commits touched `openbank-libs-domain/src/main`; lending 13, analytics 9, llm 6,
  iso20022 5, domain/feature 5, domain/payment 3, others ≤1. Roughly 45 of the 67 touched a
  business package — each of those rebuilt 57 modules to ship code that 1-11 of them use.

## Decision

We will keep `openbank-libs-domain` as the **platform core** (no rename: 57 build files and every
doc cite the name, and a rename buys nothing) and move business packages into per-bounded-context,
framework-free modules, **keeping the package namespace unchanged** (as ADR-0122 did) so a move
changes only Gradle dependency lines, never an import:

| New module | Packages | Consumers after |
|---|---|---:|
| `openbank-libs-lending` | lending, lending.compliance, lending.origination, decision | 3 |
| `openbank-libs-iso20022` | iso20022, domain.payment | ≤6 |
| `openbank-libs-agent` | llm, reasoning | 11 |
| `openbank-libs-analytics` | analytics, synthetic, domain.feature | ≤5 |
| `openbank-libs-cases` | domain.case, domain.cards (cards has 0 consumers — delete instead if still unused at move time) | 1 |

`product`, `identity`, `contact` (3 consumers each, ≤256 LOC) stay in core for now: a module per
tiny package is the over-fragmentation ADR-0122 rightly rejected. They move only when their change
rate justifies it.

**Core rule:** a core module never depends on a bounded-context module. The dependency direction is
`libs-<context> → libs-domain`, never the reverse, and `openbank-libs-runtime` may not `api()`-re-export
a context module.

### CI fan-out, expected

| Change lands in | Modules rebuilt today | After |
|---|---:|---:|
| lending / decision | 57 | 3 |
| iso20022 / domain.payment | 57 | ≤6 |
| llm / reasoning | 57 | 11 |
| analytics / synthetic / feature | 57 | ≤5 |
| case / cards | 57 | 1 |
| any core package | 57 | 57 (unchanged) |

Over the 67-commit window above: today ≈ 67 × 57 = 3,819 module-rebuilds. After, ≈ 22 core commits
× 57 + business commits × their consumer counts (≈ 14×3 + 8×6 + 7×11 + 15×5 + 2×1 ≈ 240) ≈ 1,500,
a **~60 % reduction** — an upper bound on the saving, since a commit touching both a business and a
core package still pays 57.

### Migration plan (one PR per phase; no code moves while the current wave of libs PRs is in flight)

- **Phase 0 (this ADR)** — decision + gate design. No code moves.
- **Phase 1 — lending + iso20022.** Zero imports from `openbank-libs-runtime`/`-temporal` today, so
  they move with no runtime change: create the module, `git mv` sources and tests, add the module to
  consumers' `build.gradle.kts`, add it to `LIBS_MODULES` in `libs-change-dependents.sh`. Both are
  money-path-adjacent (lending-service, sepa-*, swift, domestic-payment): 2 approvals.
- **Phase 2 — agent + analytics.** Blocked on runtime coupling: `openbank-libs-runtime` imports
  `llm` (4 sites), `synthetic` (6), `domain.feature` (3), `contact` (2), `governance` (1). The
  runtime adapters for those packages move with them (into a `-runtime` sibling of the context
  module, or into the consuming services) before the domain side can leave core.
- **Phase 3 — cases/cards**, and remove dead packages found on the way.
- **Phase 4 — enforce** the gate below; flip it from advisory to enforced once phases 1-3 land.

### Versioning trade-off

No libs module has a `version.txt` today (ADR-0122 Phase 3 / ADR-0049 left it open). This ADR does
**not** introduce one. Pro of versioning: a consumer could pin and rebuild only when it bumps, making
fan-out zero by default. Cons: the regression-lag problem `libs-change-dependents.sh` was written to
close (#2983 — a fix reaching consumers weeks apart, carried by unrelated PRs) comes back by design,
plus release-please registration for ~7 more components. The split captures most of the saving
while keeping "every consumer runs the current libs"; versioning stays a separate ADR if the core's
own 57-module fan-out becomes the bottleneck.

## Alternatives considered

- **Status quo** — rejected: roughly two thirds of libs-domain commits are business code that
  rebuilds the fleet, and the tax grows with every bounded context added.
- **Move business code into the owning service** (e.g. lending into `openbank-lending-service`) —
  right for single-consumer packages (`decision`, `domain.case`, `domain.feature`), and remains an
  option per package at move time; rejected as the general rule because lending (3), iso20022 (5)
  and llm (11) are genuinely shared and a service cannot be a library for another.
- **Publish-versioned libs** — rejected here; see *Versioning trade-off*.
- **One module per package** — rejected for the reason ADR-0122 gave: graph weight without benefit
  for packages with ≤3 consumers and ~1 change per quarter.

## Consequences

**Positive**
- A lending/iso20022/agent change rebuilds 1-11 modules instead of 57; fewer unrelated services
  re-deploy for code they do not execute.
- Ownership becomes visible in the build graph: a new bounded context gets its own module, not a
  package in core.

**Negative**
- ~5 more Gradle modules, and `LIBS_MODULES` in `libs-change-dependents.sh` must list them (a module
  missing there is the #2983 silent-no-rebuild defect again — the gate below checks it).
- Phase 2 needs runtime adapters to move first; the split is not purely mechanical there.

**Neutral**
- Package names do not change; no source import changes in any consumer.

**Gate.** A new checker `check-libs-core-purity.py` (gate `libs-core-purity`, group `kotlin`,
advisory in phase 1, enforced in phase 4) fails when (a) a file under `openbank-libs-domain/src/main`
declares a package on the moved-out list, (b) `openbank-libs-domain` or `openbank-libs-runtime`
`build.gradle.kts` declares `api`/`implementation(project(":openbank-libs-<context>"))`, or (c) an
`openbank-libs-*` module with `src/main` is absent from `LIBS_MODULES`. Self-test: a fixture adding
`package com.openbank.libs.lending` to core must fail.

## Delivery check

```bash
# (1) no business package left in core — expect empty
git grep -lE '^package com\.openbank\.libs\.(lending|decision|iso20022|llm|reasoning|analytics|synthetic|domain\.(feature|payment|case|cards))' -- openbank-libs-domain/src/main
# (2) a lending-module change rebuilds only its declarers — expect 3
grep -lE '^\s*(implementation|api)\(project\(":openbank-libs-lending"\)\)' openbank-*/build.gradle.kts | wc -l
# (3) gate present and enforced
grep -A6 'id: libs-core-purity' .github/gates/gates.yaml | grep 'mode: enforced'
```

Baseline commands for the Context numbers: consumers via
`git grep -lE 'import com\.openbank\.libs\.<pkg>[.]' -- 'openbank-*/src/*.kt' | grep -v '^openbank-libs' | cut -d/ -f1 | sort -u | wc -l`;
declarers via `grep -lE '^\s*(implementation|api)\(project\(":openbank-libs-domain"\)\)' openbank-*/build.gradle.kts | wc -l`.

## Compliance impact

- PCI DSS: not applicable — build-graph change only; `domain.cards` has no consumers.
- DORA: not applicable — no runtime behaviour changes, only which modules are rebuilt.
- GDPR: not applicable — no personal-data processing changes.
- PSD2: not applicable — iso20022 code moves unchanged; no API or SCA change.
- CNB: not applicable — no reporting or prudential logic changes.

## References

- ADR-0122 (framework-axis split), ADR-0014, ADR-0049, ADR-0028, ADR-0139
- `.github/scripts/libs-change-dependents.sh` (#2983)
