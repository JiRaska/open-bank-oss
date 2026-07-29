---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, ml, compliance, ai-agents]
summary: "Credit AI is Annex III 5(b) high-risk; Art. 9-15 bind to shipped platform machinery (registry, feature store, evidence chain, HITL, DST), with an Art. 5 prohibition gate and an Art. 26 deployer package."
---

# ADR-0216 — EU AI Act high-risk compliance for credit AI systems

## Context

Regulation (EU) 2024/1689 (the **AI Act**) classifies *"AI systems used to evaluate the
creditworthiness of natural persons or establish their credit score"* as **high-risk,
Annex III point 5(b)**. OpenBank's credit stack — the ADR-0142 ML decisioning engine
and every model promoted on the ADR-0139 platform for credit — falls squarely into
that classification. The obligations are **staged, and two stages already apply**:
prohibited practices (Art. 5) and AI literacy (Art. 4) have been in force since
**2 February 2025**, GPAI duties since 2 August 2025 — both already binding on the
platform today (the ADR-0139 fraud models and ADR-0031 agents are live scope), not
future work. The next gate is **2 August 2026**, when Annex III high-risk obligations
(Art. 9–15) apply to newly placed systems — exactly as the first credit model would
enter enforce.

Two properties of this platform make the Act an *opportunity* rather than a tax — but
only if the mapping is decided up front:

- The Chapter III controls (Art. 9–15) are **already 80 % built as platform
  machinery**: model registry and provenance (ADR-0141), point-in-time feature store
  with protected-attribute exclusion (ADR-0140), human oversight via four-eyes HITL
  (ADR-0142/0116), tamper-evident logging (ADR-0214/0133), deterministic simulation
  testing (ADR-0100), drift monitoring (ADR-0139). What is missing is the *binding*:
  which artefact satisfies which article, named so a conformity assessment can walk it.
- The Act's **prohibited practices (Art. 5)** and **deployer duties (Art. 26)** shape
  product design, not just paperwork: no social scoring, no exploitation of
  vulnerabilities, and every bank deploying OpenBank credit AI needs a documented
  deployer package.

**Why now:** ADR-0142 is still Proposed. Binding the AI Act obligations *before* the
first credit model is promoted is the difference between a conformity assessment and a
retrofit — and between "AI-native and lawful" and "innovative and fined" (up to
€ 35 M / 7 % turnover for Art. 5 breaches; € 15 M / 3 % for Chapter III).

## Decision

**D1 — Classification register.** Every AI system touching credit is recorded in
`openbank-lending-service` governance as an **AI-system entry** (name, purpose,
Annex III mapping, risk class, owner, models, data categories). Credit decisioning =
**high-risk (Annex III 5(b))**. The deterministic ADR-0213 policy engine is **not** an
AI system (no learned component) — recorded as such, so its lawful determinism is an
explicit, defensible position, not an oversight.

**D2 — Art. 9–15 control binding** (each obligation → the artefact that satisfies it):

| AI Act | Obligation | Platform binding |
|---|---|---|
| Art. 4 | AI literacy (in force since 2025-02-02, platform-wide) | Org-wide AI-literacy programme is an existing platform duty (fraud ML + agents already live); **credit-specific add-on**: overseer training for credit officers/checkers before ADR-0142 phase-2 enforce, completion tracked in the register |
| Art. 9 | Risk-management system | Threat model `credit-decisioning.md` (ADR-0030) + DPIA (ADR-0142) + this register; reviewed per model promotion |
| Art. 10 | Data governance & quality | ADR-0140 feature store: point-in-time correctness, protected-attribute exclusion at the feature boundary, training-set lineage in ADR-0141 |
| Art. 11 + Annex IV | Technical documentation | Generated from the ADR-0141 model registry (card, provenance, fairness metrics) — documentation is an export, not a wiki |
| Art. 12 | Record-keeping / automatic logs | ADR-0214 evidence chain (per-decision inputs-hash, versions, outcome) on the ADR-0133 tamper-evident substrate |
| Art. 13 | Transparency & instructions | Adverse-action principal reasons (ADR-0142 contract, ADR-0213 reason codes) + deployer instructions package (D4) |
| Art. 14 | Human oversight | No solely-automated adverse decisions above the significance threshold; four-eyes HITL (ADR-0142), maker ≠ checker (ADR-0116); overseers trained + able to override |
| Art. 15 | Accuracy, robustness, cybersecurity | DST subset invariant (ML-approved ⊆ policy-approved, ADR-0100), drift auto-rollback (ADR-0139), money-path SSDLC (ADR-0030) |

**D3 — Art. 5 prohibition check as a build gate.** Before any credit model promotion:
no social scoring, no exploitation of age/disability/social situation, no
manipulative techniques — verified by the protected-attribute + proxy-feature review
(ADR-0142 fairness gate) and recorded on the model card. A model that cannot pass is
not "high-risk", it is **prohibited** — the gate fails closed.

**D4 — Deployer (Art. 26) package.** OpenBank ships a documented **deployer kit** per
release: instructions for use (Art. 13 output), human-oversight operating procedures,
input-data relevance requirements, log-retention duties, and the serious-incident
reporting path (Art. 73) — so a bank *deploying* OpenBank credit AI can meet its own
obligations without reverse-engineering ours.

**D5 — Registration & conformity.** Pre-market: conformity assessment by internal
control (Annex VI) against the D2 binding, EU database registration of the high-risk
system, and a post-market monitoring plan fed by the ADR-0139 drift pipeline and
ADR-0061 DORA metrics. Serious incidents (Art. 73) route through the existing incident
process with an AI-Act reporting SLA.

**D6 — GPAI/LLM boundary.** LLM use in credit (ADR-0217 agents) is **not** the credit
decision itself (ADR-0142 already forbids LLM underwriting); it carries its own duties:
Art. 50 transparency (users told they interact with AI), provider GPAI documentation
for any third-party model via the gateway, and guardrail evidence (ADR-0031 D6). The
boundary "LLMs assist, deterministic + ML systems decide" is the load-bearing wall
between a high-risk system we *conform* and one we cannot.

## Alternatives considered

- **Treat the AI Act as documentation-only legal work.** Cheapest quarter, most
  expensive audit: Art. 9–15 are *engineering* obligations (logs, oversight, data
  governance) that cannot be papered over. Rejected — D2 binds each article to a
  shipped artefact.
- **Avoid high-risk classification by never using ML in credit.** Lawful but
  self-disarming: the ADR-0139 platform exists precisely for this upside, and the
  deterministic floor (ADR-0213) already carries legality. Rejected — compete *with*
  conformity as a feature.
- **Wait for harmonised standards / ČNB guidance before acting.** The 2026-08-02 date
  does not wait; designing to the articles now and adopting standards as they publish
  is strictly safer. Rejected as a sequencing strategy.
- **Central AI-Act ADR for the whole platform instead of credit-scoped.** Credit is
  the platform's first Annex III system and its obligations are domain-shaped
  (creditworthiness data, adverse action); fraud (ADR-0139) has a different
  classification analysis. Rejected — per-domain registers (like ADR-0031's
  per-agent AI-Act scoping), shared machinery.

## Consequences

**Positive**
- Credit AI becomes *demonstrably* conformable: a conformity assessment walks a table
  of shipped artefacts instead of interviewing engineers.
- "AI Act-ready credit decisioning" is a sales-grade differentiator for an OSS
  banking platform — conformity as a feature, documented in the open.
- The Art. 5 gate and the LLM boundary make the two existential mistakes (prohibited
  practice; LLM deciding credit) structurally impossible, not policy-impossible.

**Negative**
- The register, Annex IV export, deployer kit and monitoring plan are real recurring
  work per model promotion — the price of the classification, paid deliberately.
- Deployer-kit quality now bounds our users' compliance, not just ours — a
  documentation surface with legal consequences.

**Neutral**
- Decision-only ADR; no model behaviour changes (ADR-0142 phasing unchanged).
- Applies to credit AI first; the D1 register pattern is reusable by fraud and KYC
  (ADR-0102) under their own classifications.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    ICT-risk alignment — AI incident reporting (Art. 73) joins the DORA
           incident process; model rollback is an operational-resilience control.
- GDPR:    Art. 22 overlaps Art. 14 (human oversight) — one HITL mechanism serves
           both; DPIA covers both regimes.
- PSD2:    not applicable.
- CNB:     consumer-credit + AI Act supervision convergence; the D4 deployer kit is
           the ČNB examination entry point.
- **EU AI Act (2024/1689):** the subject of this ADR — Annex III 5(b) high-risk;
  Art. 5 prohibition gate; Art. 9–15 binding; Art. 26 deployer package; Art. 49
  registration; Art. 72 post-market monitoring; Art. 73 serious-incident reporting;
  Annex VI conformity; GPAI/Art. 50 boundary for LLM assistance.

## References

- ADR-0142 — credit decisioning engine (the high-risk system; HITL; adverse action)
- ADR-0213 — deterministic policy floor (D1: recorded as *not* an AI system)
- ADR-0139 / ADR-0140 / ADR-0141 — ML platform, feature store, model registry
  (Art. 9/10/11 machinery)
- ADR-0214 — credit audit evidence (Art. 12 logs); ADR-0133 — tamper-evident chain
- ADR-0100 — DST (Art. 15 robustness; subset invariant)
- ADR-0116 — four-eyes (Art. 14 oversight mechanics)
- ADR-0031 — agent governance (per-agent AI-Act scoping precedent; LLM guardrails)
- ADR-0217 — credit AI agents (the GPAI/Art. 50 boundary in practice)
- ADR-0030 — threat modeling (Art. 9 risk file)
- Regulation (EU) 2024/1689 — Annex III 5(b); Art. 5, 9–15, 26, 49, 72, 73; Annex IV/VI
