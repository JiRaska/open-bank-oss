# Compliance index

Regulator- and auditor-facing compliance documentation for OpenBank. None of this is legal advice;
deploying OpenBank as a real bank requires your own licensing, compliance, and legal review.

| Document | Covers |
|----------|--------|
| [`evidence-pack.md`](./evidence-pack.md) | DORA + supply-chain evidence pack: requirement → artifact → the command an auditor runs to verify it. |
| [`finos-ccc-mapping.md`](./finos-ccc-mapping.md) | Mapping of platform controls to FINOS Common Cloud Controls (CCC) + AI Governance Framework (AIGF). |
| [`eu-ai-act.md`](./eu-ai-act.md) | EU AI Act control mapping (tracked separately, issue #1918). |
| [`cra-conformity-assessment.md`](./cra-conformity-assessment.md) | Cyber Resilience Act product classification (default category) and the conformity-assessment path (ADR-0278, issue #8488). |

See also: [`docs/bcp/`](../bcp/) (BCP / DORA / incident response), the ADR set under
[`docs/adr/`](../adr/), and the authoritative CI-enforced ruleset
[`openbank-libs/governance/rules.yaml`](../../openbank-libs/governance/rules.yaml).
