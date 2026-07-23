<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# EU AI Act compliance mapping

> **Regulation (EU) 2024/1689** (the "AI Act").
> **Artifact of record for ADR-0148** (`decision-status: accepted`, `delivery-status: planned`).
>
> **Status of this document.** This is the **interim, hand-authored** version of the mapping.
> ADR-0148 specifies that the final artifact is **generated** from
> `openbank-libs/governance/agents.yaml` (per non-negotiable rule #7, derived data is never
> hand-edited). The generator, the in-repo prompt registry, and the per-charter evals gate are
> **code deliverables that are not yet built** — they are tracked as follow-ups to ADR-0148 and to
> issue #1918. Until the generator lands, treat this file as the human-maintained baseline; once it
> lands, this file becomes generated output and must not be edited by hand.

## 1. Scope and purpose

This document maps every AI/LLM touchpoint in the OpenBank platform to its EU AI Act risk
classification and, where a high-risk classification would attach, to the Art. 9–15 obligations and
the existing platform control that satisfies (or fails to satisfy) each. It is the concrete
precondition that ADR-0142 (credit decisioning) must reference before it can move past `proposed`.

The authoritative, machine-readable source for what each agent may see, call, and do is
`openbank-libs/governance/agents.yaml` (ADR-0031). Where this narrative and that file disagree, the
YAML wins.

## 2. The headline position

**No production credit-scoring or automated individual-decisioning model governed by AI Act Annex
III exists in the platform today.**

- **ADR-0142 (credit decisioning engine)** is `proposed / planned` — unbuilt. Creditworthiness
  assessment of natural persons is the single Annex III(5)(b) high-risk use case this platform would
  ever host, and it does not yet exist.
- **ADR-0141 (model registry & provenance)**, the provenance substrate a high-risk model would
  depend on, is likewise `proposed / planned` — unbuilt.
- Every AI touchpoint that **is** live is a **proposal-only assistant or oversight agent**: it emits
  a proposal, finding, or narration into a human-approval path and **never** takes an autonomous
  money-path or rights-affecting decision. Under the AI Act these are limited-risk (transparency
  obligations, Art. 50) or minimal-risk systems, not high-risk Annex III systems.

Consequently **no Annex III high-risk system is in scope yet.** Section 5 documents which Art. 9–15
obligations attach *the moment* ADR-0142 ships, and which existing controls already satisfy them in
substance.

## 3. AI-system inventory

### 3.1 Agent and copilot charters (ADR-0031)

The 13 charters declared in `agents.yaml`. Global defaults apply to all: PII masked
(`security/PiiMasking`), OPA policy `deny`-by-default with `enforced: block` (a PDP connectivity
error fails safe to advisory), every action recorded as an `AI_AGENT` `AuditEvent` capturing
`model_id` / `model_version` / `prompt_hash` / `tool_calls` / `policy_decision`, and a per-agent +
global kill-switch (ADR-0031 D7).

| # | Charter (`id`) | Plane | Function | Backing ADR | Annex III risk |
|---|----------------|-------|----------|-------------|----------------|
| 1 | `compliance-officer` | control | AML / sanctions / GDPR / PSD2 oversight; proposals only | ADR-0031 | Not high-risk (limited: proposal-only oversight) |
| 2 | `ledger-domain-engineer` | development | Maintains owned money-path service via PRs; never merges | ADR-0031 | Not high-risk (minimal: SSDLC assistant) |
| 3 | `ui-assistant` | control | Admin-UI bot; answers operator questions from read-only tools | ADR-0031 | Limited-risk (Art. 50 transparency: operator-facing) |
| 4 | `rca-investigator` | control | On a firing alert, proposes a root cause (HolmesGPT, ADR-0088) | ADR-0031/0088 | Not high-risk (minimal: read-only ops) |
| 5 | `customer-copilot` | customer | Customer-facing mobile assistant; action tools emit **proposals only** | ADR-0089 | Limited-risk (Art. 50 transparency: customer-facing) |
| 6 | `finops-agent` | control | Cost-precursor detection; proposes IaC fixes | ADR-0112 | Not high-risk (minimal) |
| 7 | `devops-agent` | control | SSDLC / DORA detection; proposes durable-fix PRs | ADR-0119 | Not high-risk (minimal) |
| 8 | `control-liveness-sentinel` | control | Correlates fleet-wide liveness controls; proposes tickets/PRs | ADR-0163 | Not high-risk (minimal) |
| 9 | `governance-auditor` | control | Re-checks merged PRs against `rules.yaml` | ADR-0164 | Not high-risk (minimal) |
| 10 | `release-steward` | control | Sweeps release-please / version lockstep | ADR-0165 | Not high-risk (minimal) |
| 11 | `docs-truth-agent` | control | Greps ADR delivery-status against artifacts | ADR-0166 | Not high-risk (minimal) |
| 12 | `authz-policy-auditor` | control | Static analysis of Rego + charters for unreachable rules | ADR-0167 | Not high-risk (minimal) |
| 13 | `flaky-test-hunter` | development | Static scan of Kotlin tests for silently-dropped tests | ADR-0168 | Not high-risk (minimal) |

Charters 5–7 and 11 have dedicated services (`openbank-copilot-service`, `openbank-finops-agent`,
`openbank-devops-agent`, `openbank-docs-truth-agent`); `openbank-agent-service` hosts the shared MCP
tool-call gate for the control- and development-plane charters. Every charter's `eu_ai_act`
annotation in `agents.yaml` self-declares "Art. 9 — risk management; Art. 13 — transparency;
proposal-only, not Annex III high-risk"; this document is the human-readable expansion of those
annotations.

### 3.2 Non-agent ML touchpoints

| System | Function | Status | Annex III risk |
|--------|----------|--------|----------------|
| `openbank-fraud-service` (ADR-0084) | Real-time transaction risk scoring → `ALLOW`/`CHALLENGE`/`REVIEW`/`DECLINE`; behavioural aggregates from Kafka | `accepted/shipped`; ships a baseline ONNX model (`OnnxFraudModel`, `baseline-fraud-v1.onnx`), fails open behind a rollout flag; deterministic rule layer is the permanent floor | **Expressly excluded** from high-risk — the AI Act does not classify AI used to detect financial fraud as an Annex III high-risk system |
| ML decisioning platform (ADR-0139/0140) | Feature store + in-process ONNX serving + shadow-to-champion governance; first consumer is fraud scoring | `accepted/partial` — shadow path exists; production model serving is early and the ONNX adapter is effectively a baseline/placeholder | Minimal-risk today (shadow, non-decisioning); would inherit the *consumer's* classification if ever fed a credit model |
| Credit decisioning (ADR-0142) | Creditworthiness assessment of natural persons | `proposed/planned` — **not built** | **Would be Annex III(5)(b) high-risk** if it ships |

### 3.3 Underlying LLM provider (as-built)

Per `agents.yaml: model_gateway_as_built` (verified 2026-07-16) and ADR-0175: there is **no** LLM
gateway; agents call a single provider directly — DeepInfra (`deepseek-ai/DeepSeek-V3.2`), US-hosted,
no region pinning and no DPA. Prompt data leaves the EU, and the "synthetic data only" licence
position has **no technical control enforcing it**. This is a stated open exposure in ADR-0175 (D3
routing, §4/§5) and is recorded as a gap in §5 below, not as a satisfied control.

## 4. Classification summary

- **High-risk (Annex III):** none in scope. The only candidate, credit decisioning (ADR-0142), is
  unbuilt.
- **Limited-risk (Art. 50 transparency):** the customer- and operator-facing assistants
  (`customer-copilot`, `ui-assistant`) — users must be informed they are interacting with an AI
  system.
- **Minimal-risk:** the internal oversight / SSDLC / FinOps agents and the shadow ML platform.
- **Excluded:** fraud detection (`openbank-fraud-service`).

## 5. Obligations that attach WHEN a high-risk system (ADR-0142) ships

The following maps AI Act Art. 9–15 (high-risk provider obligations) to the platform control that
would satisfy each, drawn from the existing ADR-0031 governance substrate. These are **preconditions
ADR-0142 must reference**, not current live obligations.

| Article | Obligation | Existing control | Assessment |
|---------|-----------|------------------|------------|
| **Art. 9** | Risk-management system across the lifecycle | Agent governance (ADR-0031); charter `requires_human`; kill-switch (D7); DORA Art. 9 ICT-risk framing | **Partial** — substrate exists; a model-specific, documented risk-management process per ADR-0142 does not |
| **Art. 10** | Data governance; training/validation/test data quality; bias examination | Feature store with point-in-time correctness (ADR-0140); PII masking | **Gap** — no bias-examination or data-quality regime for a credit model exists; must be built by ADR-0142 |
| **Art. 11 / Annex IV** | Technical documentation | ADR corpus; model registry & model cards (ADR-0141, `proposed`) | **Gap** — ADR-0141 unbuilt; no model card exists yet |
| **Art. 12** | Automatic logging / record-keeping over the system's lifetime | AI-attributed hash-chained audit trail (ADR-0031 D5, ADR-0086); `AuditEvent` captures `model_id`/`model_version`/`prompt_hash` | **Strong** — the audit chain already logs every AI-attributed action; extends directly to a decisioning model |
| **Art. 13** | Transparency + information to deployers | Every proposal logged; charter `eu_ai_act` annotations; adverse-action reasons mandated by ADR-0142's own design | **Partial** — machine-readable adverse-action reasons are designed into ADR-0142 but unbuilt |
| **Art. 14** | Human oversight | Human-in-the-loop approval queue (ADR-0031 D4); `requires_human: every proposal`; ADR-0142 mandates four-eyes review of declines | **Strong** — HITL is the platform default; agents propose, humans dispose |
| **Art. 15** | Accuracy, robustness, cybersecurity | Evals gate (ADR-0148, **unbuilt**); OPA policy gate; SPIFFE identity (`AgentSvidVerifier`, partial); prompt-injection guard (partial) | **Partial** — the evals gate that would measure accuracy/robustness regression is a follow-up, not yet built |

### Cross-cutting gaps (independent of ADR-0142)

- **LLM prompt egress (Art. 10 data governance / GDPR):** prompts reach a US provider with no DPA
  and no enforced synthetic-data control (ADR-0175). This is the most material open exposure and
  would block any high-risk deployment.
- **Prompt registry (Art. 12 traceability):** `prompt_hash` is captured in every audit event but does
  not yet resolve to registered content — the ADR-0148 prompt registry is unbuilt. A follow-up.
- **Evals gate (Art. 15):** unbuilt (ADR-0148 follow-up).
- **GDPR Art. 22** (automated individual decision-making) becomes directly engaged only once ADR-0142
  lands; it is out of scope while no automated decisioning model exists.

## 6. Follow-ups (code, tracked separately)

Per ADR-0148, the following are code deliverables deferred past this document (issue #1918):

1. **Prompt registry** under `openbank-libs/governance/prompts/<agent>/<version>.md` + a CI check
   rejecting any deploy referencing an unregistered `prompt_hash`.
2. **Per-charter evals gate** blocking a model/prompt promotion on a regression against the prior
   version's pass rate (the ADR-0020 ratchet pattern applied to agents).
3. **Generator** that regenerates this document from `agents.yaml`, after which this file becomes
   derived output (rule #7) and must not be hand-edited.

## 7. References

- Regulation (EU) 2024/1689 (EU AI Act) — Art. 6, Art. 9–15, Art. 50, Annex III(5)(b).
- ADR-0148 — AI assurance: prompt registry, evals gate, and EU AI Act mapping (this document's ADR).
- ADR-0031 — AI agent governance and operations (charters, OPA gate, HITL, AI-attributed audit).
- ADR-0086 — customer payment non-repudiation and hash-chained audit trail.
- ADR-0089 — customer-facing AI assistant (`customer-copilot`).
- ADR-0084 — fraud-detection bounded context.
- ADR-0139 / ADR-0140 — ML decisioning platform / feature-store topology.
- ADR-0141 — model registry and provenance (`proposed`).
- ADR-0142 — credit decisioning engine (`proposed`) — the high-risk precondition this document gates.
- ADR-0175 — data residency and sovereignty (LLM prompt-egress exposure).
- `openbank-libs/governance/agents.yaml` — authoritative charter registry.
