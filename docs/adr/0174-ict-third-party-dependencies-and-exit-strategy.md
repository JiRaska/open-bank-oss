---
date: 2026-07-16
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [compliance, infrastructure, governance]
summary: "ICT third-party register of record with a criticality and exit position per provider; the in-cluster LiteLLM gateway is represented in GitOps, while provider exit evidence remains outstanding."
---

# ADR-0174 — ICT third-party dependencies and exit strategy

## Context

DORA Art. 28–30 requires a register of ICT third-party providers, an assessment of concentration risk,
and a documented, tested **exit strategy** for each critical one. The platform has none of these.

`docs/bcp/dora-ictrm.md` is honest about it: Art. 28 is marked `~` with "vendor risk register
(**pending**)", and the backlog lists "Vendor risk register for critical third-party ICT providers"
and "Contractual DORA Art. 30 clause" as open. `docs/strategy/07-compliance-matrix.md` maps Art. 28–30
to "Vendor register (docs/)" — **that file does not exist**. The recorded Art. 30 arrangements are
"AWS ToS; Hetzner ToS; GitHub Enterprise (if applicable)" — and **Hetzner appears nowhere in the
infrastructure**, so the one register-shaped artifact we have is already wrong.

[ADR-0027](0027-cloud-agnostic-in-cluster-substrate.md) asserts portability by construction and names
an "exit/portability story" as a consequence. It is a good decision, and it has never been
demonstrated. `R-07 Third-party cloud failure` mitigates with "Cloud-agnostic architecture (ADR-0027)"
at residual risk Medium — with no test behind it.

This ADR is the register, the concentration analysis, and the exit position. It is deliberately
uncomfortable reading.

## Decision

**1. This ADR is the ICT third-party register of record.** A runtime dependency not listed here is not
governed.

| Provider | What depends on it | Criticality | If it disappears |
|---|---|---|---|
| **AWS** (EKS, KMS, ECR, S3, SQS, Route53, CloudTrail/Config, Cost Explorer) | Everything | **Critical** | Total outage. See §3 for what is genuinely locked. |
| **DeepInfra** (`api.deepinfra.com`, `deepseek-ai/DeepSeek-V3.2`) | copilot, devops-agent, control-liveness-sentinel | **Non-critical by design** | Agents degrade to deterministic fallback; money path unaffected (§4) |
| **GitHub** (`api.github.com`, ARC runners) | CI, governance agents, DORA metrics (ADR-0061) | High (build-time) | No deploys; running system unaffected |
| **Let's Encrypt** | Public ingress TLS | High | Certs expire in ≤90d; no immediate outage |
| **Container registries** (Docker Hub, quay.io, ghcr.io, registry.k8s.io) | Image pulls | Medium | Mitigated by ECR pull-through cache; Docker Hub rate-limit exhaustion under node churn is a *known* failure |
| **Slack/Teams webhooks** | Outbound oversight (ADR-0059) | Low | Alerts lost; ntfy in-cluster leg survives (ADR-0088) |
| **Maven Central / Gradle / npm** | Build only | Medium | No builds; runtime unaffected |

Self-hosted and therefore **not** third parties: Pact broker, Keycloak, GlitchTip, the PID and customer
edges — all on `open-bank.tech`.

**2. The LLM topology this platform documents is now partially represented in GitOps, and the
as-built boundary must remain explicit.**

[ADR-0031](0031-ai-agent-governance-and-operations.md) decides a hybrid gateway: **LiteLLM** as the
gateway, **vLLM** self-hosted for sensitive/air-gapped/residency work, **Anthropic** hosted for general
reasoning, **Langfuse** for LLM observability, and `sensitive_data → self_hosted` routing.

The in-cluster LiteLLM gateway and its `ai-platform` namespace are represented by
`openbank-infra/gitops/apps/litellm.yaml` and `openbank-infra/gitops/components/ai-platform/`.
The gateway fronts the configured DeepInfra and Groq routes, while vLLM, Anthropic and Langfuse
remain target topology components rather than as-built dependencies. Runtime deployment and
provider failover still require operational evidence; GitOps manifests alone do not prove either.

Two things make this worse than a normal delivery gap:
- **The agent workloads carry `LLM_GATEWAY_URL=http://litellm.ai-platform.svc:4000`** — governance-auditor,
  flaky-test-hunter, authz-policy-auditor, finops-agent, docs-truth-agent — pointing at the gateway
  service represented in GitOps.
- **~16 committed OPA bundles embed the false topology as policy data**: `tool: litellm`,
  `models: hosted {provider: anthropic}` / `self_hosted {runtime: vllm, in_cluster: true}`,
  `routing: {sensitive_data: self_hosted}`. Per `rules.yaml: opa_bundle_sync` these are machine-
  embedded, so the untruth is replicated fleet-wide and would be read by any policy consumer as fact.

**We record the real dependencies (DeepInfra and Groq behind the in-cluster gateway) as governed
ones.** D1 is delivered in the governance policy data; ADR-0031's vLLM/Anthropic/Langfuse topology
remains a *target*, not a description of the deployed estate.

**3. What is genuinely AWS-locked — stated plainly, because ADR-0027 claims portability.**

ADR-0027's decision is sound: no FaaS/PaaS in the money path; only EKS, VPC, KMS and the audit sink as
in-path AWS primitives; everything stateful runs in-cluster on OSS (CNPG, Strimzi, Keycloak, OpenBao,
Valkey). All ~40 banking services are plain Quarkus containers and would move.

But the substrate is more locked than "cloud-agnostic" suggests, and pretending otherwise is the
failure mode:
- **KMS** — envelope encryption *and* OpenBao auto-unseal. Moving clouds means re-keying the unseal
  path (see [ADR-0172](0172-cryptographic-key-management-and-lifecycle.md)).
- **S3 Object Lock COMPLIANCE** — the WORM audit archive. Genuinely S3-specific; not a swap.
- **EKS Pod Identity** — chosen *over* IRSA for Karpenter, external-dns, CNPG barman. Deepens the lock.
- **Karpenter itself is AWS-only** — and it is the load-bearing capacity mechanism ([ADR-0173](0173-capacity-management-and-headroom.md)).
- **CloudTrail / AWS Config** — the DORA Art. 12 audit sink.
- **Cost Explorer / Budgets** — the entire ADR-0054/0062/0112 FinOps surface.

So: **the applications are portable; the platform is not.** ADR-0027's guarantee holds for the layer it
actually covers, and this ADR names the rest rather than letting "cloud-agnostic" do work it cannot.

**4. The LLM dependency is non-critical *by construction*, and that is a real control.**
`LlmDiagnosisAdapter` reads its API key via an optional lookup, so an unseeded key **degrades to a
deterministic fallback rather than crashlooping**; `chat()` returns null on any failure; timeouts are
10s connect / 60s request. A DeepInfra outage silently degrades AI agents and touches no payment. This
is the de-facto Art. 28 third-party-failure control, and it is documented only in Kotlin KDoc — so it
is documented here.

The `ModelProvider` port (ADR-0031) is what makes provider substitution a config change. Note it is
*also* the Apache/AGPL licensing seam ([ADR-0136](0136-agent-services-agpl-in-repo-open-core.md)):
provider-swappability is load-bearing for the open-core model, not only for exit. Two obligations, one
seam.

**5. Exit position: honest, per class.**
- **Applications** — portable today. Plain containers, OSS data plane. *Exit: re-target the gitops
  substrate.* Untested.
- **Platform** — not portable without work: KMS, S3 Object Lock, Pod Identity, Karpenter, CloudTrail.
  *Exit: a project, not a switch.* Unquantified.
- **LLM** — portable by config (`ModelProvider` + `model-endpoint`). *Exit: change one env var.* The
  cheapest exit in the estate, and the only one with no register entry.
- **GitHub** — build-time only; a running cluster survives. *Exit: unassessed.*

**We do not claim a tested exit for any of them.** ADR-0027's "portability preserved" is an
architectural property, not evidence.

## Decisions to deliver

- **D1 — Correct the OPA policy data.** ~16 bundles asserted a litellm/vLLM/Anthropic topology that did
  not exist. This is not a doc bug: it is machine-embedded policy data claiming a residency-routing
  control is in force when it is not. **Highest priority here** — a false control claim is worse than
  an absent one. *(Delivered in governance policy; live rollout remains an operational check.)*
- **D2 — Remove or implement `LLM_GATEWAY_URL`.** The agent workloads now point at the in-cluster
  `litellm.ai-platform.svc:4000` service represented in GitOps. *(Delivered in manifests; live reachability remains an operational check.)*
- **D3 — Write the register as a maintained artifact.** `docs/strategy/07-compliance-matrix.md` points
  at a "Vendor register (docs/)" that does not exist, and `dora-ictrm.md` lists **Hetzner**, which is
  not used. §1 is the register today; it needs an owner and a review cadence. *(Pending)*
- **D4 — Test one exit.** The LLM is the cheapest (§5) and the most valuable to prove: swap
  DeepInfra → a second provider in a sandbox and confirm the agents keep working. An untested exit is
  an assertion. *(Pending)*
- **D5 — Reconcile ADR-0031's topology with reality.** Either build the gateway or amend ADR-0031 to
  record DeepInfra-direct as the as-built, in the house style ADR-0027 already set with its "As-built
  deltas (honest record)". *(Pending)*

## Alternatives considered

- **Multi-cloud.** The textbook Art. 28 answer to concentration risk. Rejected: this is a
  single-maintainer reference implementation on one sandbox account. Multi-cloud would double the
  operational surface to hedge a risk the platform does not carry (it holds no real money and no real
  customers). ADR-0027's in-cluster-OSS substrate is the proportionate control: it keeps the *option*
  without paying for it.
- **Add a second LLM provider now.** Cheap and tempting. Rejected as premature: with the agents
  already degrading safely (§4) a failover adds a code path that would rarely run and never be tested
  — the classic untested-failover trap. D4 (prove the swap works) buys the same assurance for less.
- **Self-host the LLM (vLLM), as ADR-0031 decided.** Would resolve both the third-party and the
  residency exposure ([ADR-0175](0175-data-residency-and-sovereignty.md)). Rejected *for now* on cost:
  GPU capacity for a demo estate is not justifiable, and ADR-0027's FinOps posture is why the sandbox
  is Graviton-spot in the first place. Kept as the stated target; D5 makes the gap explicit rather
  than letting the ADR imply it is done.
- **Say nothing until there is a register.** Rejected: the audit already found the gap, and an empty
  register is a smaller problem than a wrong one. §1 is a real register that a reader can act on.

## Consequences

**Positive**
- There is now a register. It is short and it is true, which beats the two artifacts that pointed at it
  and were wrong.
- The gap between ADR-0031's decided topology and the running system is written down — with the
  policy-data corruption (D1) called out as a security-relevant issue rather than a docs nit.
- "Cloud-agnostic" stops doing work it cannot: §3 separates the portable applications from the locked
  platform.

**Negative**
- The honest answer to "do you have a tested exit plan?" is now, in writing, **no**. That is a real
  disclosure for a public repo — and it is what an auditor would find in an hour anyway.
- §2 documents that a documented control (residency routing) does not exist. Better here than
  discovered by someone relying on it.

**Neutral**
- No runtime change. D1–D5 carry the delivery.

## Compliance impact

- **DORA Art. 28 (register + concentration risk):** §1 is the first register artifact. Concentration is
  AWS, and §3 is honest about how deep. Currently `~` in `docs/bcp/dora-ictrm.md`; this ADR is what
  that entry should point at.
- **DORA Art. 29 (concentration):** single cloud, single region, single LLM provider, single Karpenter
  replica. Accepted for a reference implementation; named so it is a decision rather than an oversight.
- **DORA Art. 30 (contractual arrangements):** genuinely absent. "AWS ToS" is not an Art. 30
  arrangement, and the recorded "Hetzner ToS" is for a provider that is not used. D3.
- **GDPR Chapter V:** the DeepInfra dependency is also a cross-border transfer question — owned by
  [ADR-0175](0175-data-residency-and-sovereignty.md), not here.
- **PCI DSS / CNB:** not applicable.

## References

- [ADR-0027](0027-cloud-agnostic-in-cluster-substrate.md) — the portability decision §3 bounds
- [ADR-0031](0031-ai-agent-governance-and-operations.md) — the LLM topology §2 shows is unbuilt
- [ADR-0136](0136-agent-services-agpl-in-repo-open-core.md) — why `ModelProvider` is also a licensing seam
- [ADR-0172](0172-cryptographic-key-management-and-lifecycle.md) — the KMS keys that deepen the AWS lock
- [ADR-0173](0173-capacity-management-and-headroom.md) — Karpenter, the AWS-only capacity mechanism
- [ADR-0175](0175-data-residency-and-sovereignty.md) — where the LLM data goes
- [ADR-0134](0134-business-continuity-and-dora-ictrm.md) — BCP/ICT-RM; `docs/bcp/dora-ictrm.md` is its artifact
- `docs/audits/2026-07-16-platform-audit.md` §3.3, §6.3 — the gap this ADR closes
