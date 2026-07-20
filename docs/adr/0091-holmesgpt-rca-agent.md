---
date: 2026-06-14
decision-status: accepted
delivery-status: shipped
authors: [@JiRaska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, observability, governance]
summary: "HolmesGPT runs as the AI root-cause-analysis agent over the observability stack, read-only and on-demand only (no Alertmanager auto-trigger), charter-bound so every output is a proposal, never auto-remediation."
---

# ADR-0091 — HolmesGPT: AI root-cause-analysis agent over observability signals

> **Amendment 2026-06-19 — deployed in PR #1025.** HolmesGPT is live in the sandbox via
> `openbank-infra/gitops/apps/holmesgpt.yaml`. The `rca-investigator` charter is active in
> `agents.yaml`. Read-only, on-demand; Groq `llama-3.3-70b-versatile` in sandbox (production
> model deferred, FinOps-gated per ADR-0054). Loki/Tempo toolsets are a follow-up once a
> Grafana SA token is provisioned (no new credential scope in this cut).

**Relates to:** ADR-0031 (AI-agent governance), ADR-0077 (Three-Pillar Observability),
ADR-0088 (Observability Extension), ADR-0089 (Customer-facing AI assistant / model gateway),
ADR-0008 (OpenTelemetry)

## Context

The Dynatrace-parity sweep (tracking #1022) closed the gaps between our OSS LGTM(P) stack and a
commercial APM one capability at a time: DB-layer metrics (#1017), synthetics (#1019), OTel
auto-instrumentation (#1021). The one capability pure OSS infra cannot reproduce is Dynatrace's
**causal AI (Davis)** — given an alert, automatically correlating signals into a proposed root
cause. Our signals are healthy (Prometheus, Loki, Tempo, exemplars, service-graphs per
ADR-0077/0087/0088) but turning them into an explanation is still manual on-call work.

PR #1025 deployed **HolmesGPT** (Robusta, Apache-2.0) as the "Davis-lite" analogue and added a
`rca-investigator` charter to `agents.yaml`. That charter cited ADR-0079/0088, but neither
documents the decision: ADR-0079 only mentions the agent-service producing release-note summaries,
and ADR-0088's observability extension predates the sweep and never considered HolmesGPT. Deploying
a new **LLM-based agent that reads production observability** is exactly the kind of choice ADR-0031
("agents propose, governance disposes") expects to be recorded as a decision, not buried in a Helm
manifest. This ADR records it.

## Decision

We will run **HolmesGPT** as the AI root-cause-analysis agent over the observability stack, deployed
via GitOps (`openbank-infra/gitops/apps/holmesgpt.yaml`) and governed by the `rca-investigator`
charter (ADR-0031).

- **Read-only, on-demand only.** HolmesGPT runs the *server* (investigation API) with read-only
  toolsets — Kubernetes cluster state and the in-cluster Prometheus. It does **not** subscribe to
  Alertmanager: auto-on-alert RCA (a Robusta feature) is deliberately avoided, so Holmes makes **no
  autonomous LLM calls** — it only reasons when explicitly queried. This bounds both LLM cost and
  blast radius. Wiring an Alertmanager webhook or an admin-UI "explain this alert" button to
  `/api/investigate` is a follow-up, not part of this decision.
- **Model-agnostic via litellm (ADR-0031 D6 / ADR-0089).** The sandbox runs the same free Groq model
  (`groq/llama-3.3-70b-versatile`, `temperature: 0`) the agent-service already uses, with the key
  reused from the existing `openbank/agent-service` OpenBao path — **no new credentials**. A
  production-grade model is deferred on FinOps grounds (ADR-0027/0054), behind the same gateway.
- **Charter-bound (ADR-0031).** The `rca-investigator` charter is control-plane, deny-by-default:
  `query.observability.readonly` only; `*.write`, `money.*`, `gh.pr.*`, `secrets.read.raw` denied;
  PII masked; every output is a **proposal** into the admin-UI approval queue — **no
  auto-remediation**. HolmesGPT holds strictly less privilege than a human on-call engineer.

## Alternatives considered

- **Robusta auto-on-alert RCA** — HolmesGPT's parent project can subscribe to Alertmanager and
  investigate every firing alert automatically. Rejected for the first cut: we already run
  Alertmanager→GoAlert→ntfy (ADR-0088 D1) for paging, and unbounded autonomous LLM calls are an
  open-ended cost and blast-radius risk for a bank. On-demand keeps a human in the loop.
- **Commercial Davis (Dynatrace) / external APM AI** — already rejected fleet-wide in ADR-0077 on
  cost and data-residency grounds; an LLM that ingests cluster state and metrics must stay on the
  OSS, self-hostable substrate.
- **Custom in-house RCA agent** — building causal correlation on top of our own agent-runtime
  (ADR-0031) is months of work for a capability HolmesGPT already provides as an Apache-2.0 chart.
  Revisit only if HolmesGPT proves limiting.
- **No RCA agent (status quo)** — leaves the Davis-parity gap open; on-call keeps correlating
  signals by hand. Rejected: the sweep's explicit goal was to close that gap.

## Consequences

**Positive**
- Closes the last Dynatrace-parity gap (causal RCA) on a strictly OSS, self-hosted, in-VPC stack.
- The decision, model choice, and read-only/on-demand scope are now an auditable governance fact,
  not latent in a Helm values file.
- Zero new credentials and zero new autonomous spend: on-demand queries against a free sandbox model.

**Negative**
- A new LLM-using workload to operate; production model selection and its FinOps envelope are still
  open (deferred).
- Read access to cluster state and metrics is a sensitive surface even when read-only — covered by
  the charter's PII masking and the deny-by-default tool tier, and by the in-cluster network posture.

**Neutral**
- No query-path change: HolmesGPT reads the same in-cluster Prometheus the rest of the stack uses.
- Grafana/Loki/Tempo toolsets are an easy follow-on once a Grafana SA token is provisioned; kept out
  here to avoid a new secret in the first cut.

## Compliance impact

- PCI DSS: not applicable (no cardholder data; PII masked per charter).
- DORA:    Art. 17 — supports incident response/reconstruction by accelerating root-cause analysis;
  remains advisory (proposal-only, human disposes).
- GDPR:    Art. 5/25 — PII masked in agent scope (ADR-0031 defaults); no customer PII in metrics/k8s state.
- PSD2:    not applicable.
- CNB:     not applicable.

## References

- `openbank-infra/gitops/apps/holmesgpt.yaml` — Helm Application (read-only toolsets, on-demand)
- `openbank-infra/gitops/components/external-secrets/es-holmes-llm.yaml` — LLM key (reused, no new secret)
- `openbank-libs/governance/agents.yaml` — `rca-investigator` charter (ADR-0031)
- PR #1025 (HolmesGPT deploy), tracking #1022 (Dynatrace-parity sweep)
- ADR-0031 (AI-agent governance), ADR-0077/0087/0088 (observability), ADR-0089 (model gateway)
