# OpenBank Strategy Documentation

> Last updated: 2026-05-26
> Status: **v0.1** — initial strategic documentation suite.

This directory contains the strategic, architectural, and compliance reasoning behind OpenBank. It is intended for:

- **Maintainers and contributors** — to anchor implementation decisions
- **Operators** — to understand what they are deploying and what controls they inherit
- **Auditors and regulators** — to see the documented controls baseline
- **Prospective adopters** — to evaluate fit for purpose

## Documents

| # | Document | Purpose |
|---|---|---|
| 00 | [Executive Summary](00-executive-summary.md) | Project mission, scope, target audience, what we are not |
| 01 | [BIAN Service Domain Mapping](01-bian-service-domain-mapping.md) | Map OpenBank services to the BIAN Service Landscape; gap analysis |
| 02 | [Competitor Analysis](02-competitor-analysis.md) | Core banking market segmentation; positioning vs Fineract, Mambu, Thought Machine, 10x, Temenos, BaaS providers |
| 03 | [Technology Radar](03-technology-radar.md) | Adopt / Trial / Assess / Hold for our tech stack |
| 04 | [Security Baseline](04-security-baseline.md) | Defence-in-depth controls; NIST CSF, OWASP ASVS L3, PCI DSS, DORA alignment |
| 05 | [Resilience Design](05-resilience-design.md) | Failure domains, RTO/RPO targets, resilience patterns, chaos programme |
| 06 | [Scalability Targets](06-scalability-targets.md) | Sandbox / Tier-B / Tier-A workload profiles; latency and throughput budgets |
| 07 | [Compliance Matrix](07-compliance-matrix.md) | PSD2, DORA, PCI DSS v4.0, GDPR, 5AMLD, CNB Act 21/1992, eIDAS 2.0 — requirements traced to capabilities and verification |
| 08 | [Risk Register](08-risk-register.md) | Top-20 project risks with mitigations and owners |
| 09 | [Roadmap M1-M7](09-roadmap-M1-M7.md) | Outcome-oriented milestones with acceptance criteria |

## How these documents relate

```
                  00 Executive Summary
                         |
                         v
   +---------------------+---------------------+
   |                     |                     |
01 BIAN              02 Competitors        03 Tech Radar
   |                                          |
   +---------------------+---------------------+
                         |
                         v
   04 Security  ---  05 Resilience  ---  06 Scalability
                         |
                         v
                 07 Compliance Matrix
                         |
                         v
                  08 Risk Register
                         |
                         v
                 09 Roadmap M1-M7
```

Read top-to-bottom for the strategic story.
Read bottom-up if you came in via the roadmap and want to understand the why.

## Companion documentation

- **Architecture Decision Records:** [`../adr/`](../adr/) — single-page records of architecturally significant decisions (ADR-0001 onward).
- **Runbooks:** [`../runbooks/`](../runbooks/) — operational playbooks (to be populated in M4).
- **API contracts:** [`../../openbank-contracts/`](../../openbank-contracts/) — OpenAPI 3.1 and AsyncAPI 3.0 specifications.
- **Governance:** [`../governance/`](../governance/) — project governance, maintainer responsibilities, decision process.

## Versioning

Strategy documents are versioned with the project. Significant changes are recorded in commit history. When a document is materially revised, the "Status" line at the top is updated; previous versions remain in git history.

## Review cadence

- Strategy documents: reviewed quarterly by maintainers; minor updates as needed.
- Compliance matrix: reviewed quarterly + on every published RTS / implementing act.
- Technology radar: reviewed quarterly; ring movements require recorded rationale.
- Roadmap: reviewed at every milestone completion + quarterly.
- Risk register: reviewed quarterly + on every incident.

## Contributing to strategy

Improvements to these documents are welcome:

1. Open an issue describing the proposed change and rationale.
2. Send a PR with the document update.
3. Maintainers review and merge.

For substantive architectural changes, prefer opening an ADR before changing the strategy document.

## Disclaimer

These documents describe the **target** posture for the OpenBank reference implementation. They are not warranties, certifications, or legal advice. Operators are responsible for verifying that their deployment meets the regulatory requirements applicable in their jurisdiction.
