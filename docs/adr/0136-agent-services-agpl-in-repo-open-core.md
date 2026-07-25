---
date: 2026-06-29
decision-status: accepted
delivery-status: shipped
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [licensing, ai-agents, governance]
summary: "The four AI agent services are relicensed AGPL-3.0-only in-repo with a parallel commercial licence while the rest of the platform stays Apache-2.0, replacing the ADR-0031 separate-repo plan with a per-component open-core boundary."
---

# Agent services licensed AGPL-3.0-only in-repo (open-core)

> **Membership superseded by [ADR-0197](0197-agpl-open-core-boundary-covers-the-whole-agent-plane.md).**
> The decision below — AGPL-3.0-only open-core in-repo, with a parallel commercial licence,
> kept safe by dependency direction — is still in force. Its **enumeration is not**: the
> boundary now covers the whole agent plane (twelve modules today, not the four named here),
> and the authoritative list is `dependencies.license_boundary_exceptions[0].agpl_modules` in
> `openbank-libs/governance/rules.yaml`. Read "the four agent services" below as
> point-in-time. Enumerating them in prose is what drifted (#2280).

## Context

OpenBank's platform is licensed Apache-2.0 (ADR-0123, superseding the original
MPL-2.0 of ADR-0012). The AI agent components are the part of the project intended
for commercialization. ADR-0031 D8 originally planned to keep the commercialized
"agent runtime" in a **separate repository/module** under AGPL-3.0 + a parallel
commercial licence, with only the `ModelProvider` port crossing the Apache/AGPL seam.

That separate repo was never created. In practice the agent code lives in this
monorepo (`openbank-agent-service`, `openbank-copilot-service`, `openbank-devops-agent`,
`openbank-finops-agent`) and — until now — carried Apache-2.0 SPDX headers, which
contradicted the stated open-core intent. Before the repository goes public, the
licence of the agent code must match reality and intent.

## Decision

We will license the **four AI agent services in-repo under `AGPL-3.0-only`**, with a
**parallel commercial licence** available from the maintainer (open-core / dual-license):

- `openbank-agent-service`, `openbank-copilot-service`, `openbank-devops-agent`,
  `openbank-finops-agent` → **AGPL-3.0-only** (every source file carries the
  `SPDX-License-Identifier: AGPL-3.0-only` header plus a commercial-availability note).
- The rest of the platform (accounts, ledger, payments, PSD2, `openbank-libs`, admin-UI,
  infra, docs, …) **remains Apache-2.0** (ADR-0123).
- This makes OpenBank a **multi-license repository**: licence texts live in `LICENSES/`
  (`Apache-2.0.txt`, `AGPL-3.0-only.txt`); the root `LICENSE` is Apache-2.0; each agent
  service carries its own `LICENSE` pointer.

This **supersedes the ADR-0031 D8 "separate repo" plan**: the open-core boundary is now
a per-component licence boundary inside this repo, not a repository boundary.

## License boundary (why this is safe)

AGPL is copyleft, so it must not contaminate the Apache-2.0 platform. The boundary is
enforced by **dependency direction**, verified at decision time:

- No Apache-2.0 module takes a build/compile dependency on any agent service
  (`project(":openbank-{agent,copilot,devops,finops}*")` is declared nowhere outside the
  agent services themselves); no non-agent Kotlin imports the agent packages.
- The agent services depend only on `openbank-libs` (Apache-2.0) — copyleft may consume
  permissive code, which is fine.
- admin-UI and other services interact with agents only over **HTTP/network**, which AGPL
  treats as use, not linking.

`rules.yaml` records this: AGPL-3.0-only is denylist-excepted for the four agent paths, and
a boundary rule forbids any Apache module from build-depending on them.

## Contribution / dual-licensing

Contributions remain under the **DCO** (no CLA) for now (ADR-0012/0123). The maintainer is
currently the sole copyright holder of the agent code and can therefore offer the parallel
commercial licence. A CLA for the AGPL components will be introduced if/when external
contributors submit to those services, so dual-licensing rights are preserved.

## Alternatives considered

- **Keep the separate-repo plan (ADR-0031 D8).** Rejected: the repo doesn't exist; shipping
  a public README/NOTICE/badge that advertises a non-existent repo is an overclaim and a
  credibility risk.
- **Leave the agents Apache-2.0.** Rejected: contradicts the open-core/commercial intent and
  gives away the one component meant to be commercializable.
- **AGPL the whole repo.** Rejected: kills permissive adoption of the banking platform, which
  is the project's main value proposition (ADR-0123).

## Consequences

- The agents you can see are now genuinely AGPL-3.0-only; the README badge and License
  section are accurate.
- Downstreams may use the platform permissively (Apache-2.0) but must comply with AGPL (incl.
  the network-use clause) if they deploy or modify the agent services, or obtain a commercial
  licence.
- `scripts/add-license-headers.sh` must be made path-aware (Apache by default, AGPL for the
  four agent paths) so new agent files get the right header — tracked as a follow-up.

## Compliance impact

- PCI DSS: not applicable — source licensing decision, no cardholder data in scope.
- DORA:    not applicable — licence boundary only, no ICT risk or resilience control changed.
- GDPR:    not applicable — no personal data involved in licence headers or texts.
- PSD2:    not applicable — no payment or account-access behaviour is changed.
- CNB:     not applicable — no regulated banking function or reporting is affected.
