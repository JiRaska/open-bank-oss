---
date: 2026-07-25
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [licensing, ai-agents, governance]
summary: "The AGPL-3.0-only open-core boundary is a property — the agent plane, which moves no money — not ADR-0136's fixed four; membership lives canonically in rules.yaml agpl_modules (today twelve) and is never copied into NOTICE or README."
---

# ADR-0197 — AGPL open-core boundary covers the whole agent plane, enumerated in rules.yaml

## Context

ADR-0123 licensed the platform Apache-2.0. ADR-0136 then carved out the commercializable
part as AGPL-3.0-only in-repo with a parallel commercial licence, and did so by
**naming four modules**: `openbank-agent-service`, `openbank-copilot-service`,
`openbank-devops-agent`, `openbank-finops-agent`.

The agent plane did not stop at four. Six more control-plane and development-plane
agents shipped afterwards — ADR-0163 (`control-liveness-sentinel`), ADR-0164
(`governance-auditor`), ADR-0165 (`release-steward`), ADR-0166 (`docs-truth-agent`),
ADR-0167 (`authz-policy-auditor`), ADR-0168 (`flaky-test-hunter`) — each the same
architectural family as the finops-agent (ADR-0112) and devops-agent (ADR-0119) that
ADR-0136 had already covered: read-only detectors that propose a ticket or a PR for a
human to approve. Two agent-facing bank-side services followed, and each *did* record its
licence in its own ADR: `openbank-mcp-service` (ADR-0181) and `openbank-ap2-service`
(ADR-0193).

Whoever built the six gave them `SPDX-License-Identifier: AGPL-3.0-only` headers and
their own `LICENSE` file. That matched the intent, but **no ADR recorded the decision**,
and because ADR-0136's enumeration was the only written statement of membership, every
other description of the boundary drifted from the tree (issue #2280):

| Source | AGPL modules named |
|---|---|
| SPDX headers + per-module `LICENSE` | **12** |
| `rules.yaml` | 10 |
| `NOTICE` | 4 |
| `README.md` | 4 |

`NOTICE` was the damaging one. It enumerated four and stated they "are NOT covered by the
Apache-2.0 LICENSE/NOTICE" — which tells a downstream adopter of a nominally Apache-2.0
monorepo that the other eight AGPL modules *are* Apache-2.0. All twelve carry a
`version.txt`, so all twelve are distributed as released artifacts. An SPDX scanner reads
that as AGPL contamination of an Apache-2.0 distribution.

PR #2281 reconciled every declaration and added an enforced gate
(`.github/scripts/check-license-headers.py`). **Why this ADR, now:** that gate enforces a
canonical list whose *membership criterion* was still unwritten. A canonical list with no
stated rule for joining it is a list nobody can correctly extend — the next agent service
would be a coin flip, which is how the drift started. Enumerating four in prose was the
root defect; replacing it with a longer prose enumeration would only reset the clock.

## Decision

We will define the AGPL-3.0-only open-core boundary as a **property of a module, not a
fixed list of names**:

> A module is licensed **AGPL-3.0-only + a parallel commercial licence** if and only if it
> belongs to the **agent plane** — it exists to run, govern, or serve AI agents — **and it
> moves no money**. Everything else in this repository is Apache-2.0 (ADR-0123).

Membership is **enumerated in exactly one place**:
`openbank-libs/governance/rules.yaml` → `dependencies.license_boundary_exceptions[0].agpl_modules`.
`NOTICE`, `README.md` and any future licensing prose state the rule and **point at** that
list; they do not copy it. The second hand-maintained copy is the drift, so the fix is to
have no second copy rather than to keep it correct.

Equivalently, and checkably from the tree alone: **a module is AGPL-3.0-only iff it
contains its own `LICENSE` file.**

The twelve current members, by family:

- **Customer- and staff-facing agents** — `agent-service`, `copilot-service` (ADR-0136)
- **Control-plane agents** — `devops-agent` (ADR-0119), `finops-agent` (ADR-0112),
  `control-liveness-sentinel` (ADR-0163), `governance-auditor` (ADR-0164),
  `release-steward` (ADR-0165), `docs-truth-agent` (ADR-0166),
  `authz-policy-auditor` (ADR-0167)
- **Development-plane agent** — `flaky-test-hunter` (ADR-0168)
- **Agent-facing bank-side services** — `mcp-service` (ADR-0181), `ap2-service` (ADR-0193)

Two invariants make the boundary safe, and both are enforced by the gate:

1. **No Apache-2.0 module may take a build or compile dependency on an AGPL module.** The
   agent plane is reached only over HTTP, which the AGPL treats as use rather than linking.
   The AGPL modules depend only on `openbank-libs-*` (Apache-2.0), which copyleft permits.
2. **No `money_path_services` entry may ever be AGPL-3.0-only.** This is what makes the
   licence split honest rather than merely legal: an adopter can run the entire banking
   platform — all 20 money-path services, 51 of 63 modules — under Apache-2.0 alone. The
   agent plane is an optional layer on top. `ap2-service` sits inside the boundary precisely
   because ADR-0193 established it is evidence-only and moves no funds; the day a future ADR
   gives it fund-moving execution, this invariant forces the licence question to be reopened
   rather than silently inherited.

This **does not supersede ADR-0136**, whose decision — that the commercializable agent code
is AGPL open-core in-repo, with a commercial alternative, kept safe by dependency direction
— remains the active decision and the rationale for this one. ADR-0197 replaces only its
*enumeration*. Where ADR-0136's prose says "four", `rules.yaml` is authoritative.

## Alternatives considered

- **Enumerate the twelve in this ADR instead of pointing at `rules.yaml`.** Rejected: this
  recreates the exact defect being fixed. ADR-0136's four-name list was accurate the day it
  was written and wrong within months; a twelve-name list in an immutable historical record
  would be wrong the day the thirteenth agent ships, and an ADR cannot be regenerated.
- **Mark ADR-0136 `superseded` by this ADR.** Rejected: its decision is still in force.
  `superseded/shipped` would tell every reader — and the DIGEST — that the AGPL open-core
  model itself had been replaced, which is false and more misleading than the stale count.
  A forward pointer in ADR-0136 carries the correction without the false signal.
- **Relicense the six control/development-plane agents to Apache-2.0**, keeping AGPL to the
  original four. Rejected: they are the same family as the already-AGPL devops- and
  finops-agents, and the agent tooling is the part of the project intended for
  commercialization (ADR-0123, ADR-0136). This would give away the moat and leave the
  boundary defined by nothing but the accident of ship date.
- **AGPL the whole repository.** Rejected in ADR-0136 and still rejected: it kills permissive
  adoption of the banking platform, which is the project's main value proposition.
- **Leave it undocumented and treat `rules.yaml` as the sole record.** Rejected: it *is* the
  sole enumeration by design, but a list with no written membership criterion cannot be
  extended correctly, and the CI gate would be enforcing a rule with no stated rationale — an
  auditor reading the gate could not tell whether a given module belongs.
- **Move the agent plane to a separate repository** (the original ADR-0031 D8 plan).
  Already rejected by ADR-0136: the repo was never created and advertising one is an overclaim.

## Consequences

**Positive**
- The boundary is now testable rather than remembered. `check-license-headers.py` compares
  every declaration — `rules.yaml` internal consistency, per-module `LICENSE` in both
  directions, per-file headers, the build-dependency edge, and that the published docs never
  enumerate a subset — and its `--selftest` proves each check can fail.
- A downstream adopter has one unambiguous answer: the bank is Apache-2.0; the agent plane is
  AGPL or commercial. The money-path invariant makes that answer durable.
- Adding an agent service is now a checklist, not a judgement call: `rules.yaml` entry, own
  `LICENSE`, run the path-aware `scripts/add-license-headers.sh`.

**Negative**
- The licence of a module is no longer readable from the ADR history alone; you must consult
  `rules.yaml`. That is the deliberate trade — one authoritative mutable list beats several
  immutable stale ones — but it does mean the ADRs alone no longer answer the question.
- The "moves no money" half of the criterion needs judgement for any future service that sits
  near the payment path. `ap2-service` shows the resolution is an explicit ADR statement, not
  an inference.

**Neutral**
- No code, API, schema or runtime behaviour changes; this is a licensing decision.
- Eight already-applied Flyway migrations inside AGPL modules keep a stale Apache-2.0 header
  because Flyway checksums the file including comments and editing one would fail the service
  at boot. They are corrected out-of-tree in `REUSE.toml` with `precedence = "override"`, and
  only a squashed baseline migration can retire those entries.
- Contributions remain under the DCO (ADR-0012, ADR-0123). ADR-0136's note still applies: a
  CLA for the AGPL components would be needed if external contributors submit to them, to
  preserve the dual-licensing right.

## Compliance impact

- PCI DSS: not applicable — source licensing decision; no cardholder data in scope.
- DORA:    not applicable — licence boundary only; no ICT risk or resilience control changes.
- GDPR:    not applicable — no personal data is involved in licence headers or texts.
- PSD2:    not applicable — no payment or account-access behaviour changes.
- CNB:     not applicable — no regulated banking function or reporting is affected.

Worth recording for a licence audit rather than a regulator: because every AGPL module ships
as a released artifact with its own `version.txt`, the AGPL network-use clause applies to an
operator who deploys or modifies any of them, unless they hold the commercial licence. The
money-path invariant above bounds that exposure to the optional agent layer.

## References

- ADR-0123 — relicense the platform to Apache-2.0
- ADR-0136 — agent services AGPL-3.0-only in-repo (open-core); this ADR replaces its enumeration only
- ADR-0112, ADR-0119 — finops-agent and devops-agent, the control-plane family
- ADR-0163 … ADR-0168 — the six control/development-plane agents whose licence this ADR records
- ADR-0181 (`mcp-service`), ADR-0193 (`ap2-service`) — agent-plane services that stated AGPL themselves
- ADR-0012 — DCO instead of a CLA
- Issue #2280, PR #2281 — the drift, the reconciliation, and the gate
- `openbank-libs/governance/rules.yaml` → `dependencies.license_boundary_exceptions[0].agpl_modules` (canonical list)
- `.github/scripts/check-license-headers.py`, `scripts/add-license-headers.sh`, `REUSE.toml`
- `LICENSES/AGPL-3.0-only.txt`, `LICENSES/Apache-2.0.txt`
