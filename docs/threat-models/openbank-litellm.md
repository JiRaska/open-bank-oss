# Threat Model — LiteLLM model gateway (`openbank-litellm`)

**Component:** the in-cluster LLM gateway every AI agent and the admin-ui copilot call; our own
digest-pinned, scanned and signed build of upstream LiteLLM (`openbank-infra/docker/litellm`).
**Manifests:** `openbank-infra/gitops/components/ai-platform/` (`litellm.yaml`, `litellm-config.yaml`,
`networkpolicy-litellm-egress.yaml`, `network-policies.yaml`, `postgres.yaml`)
**ADRs:** ADR-0031 (AI agent governance, guardrails), ADR-0175 (data residency — this pod is the
single egress point to model providers), ADR-0030 (threat-model discipline)
**Review cadence:** on any change to the provider list, the egress policy, the ingress allow-list,
or a LiteLLM major/minor upgrade.

Not a money-path service. It is modelled because it is the **only pod in the fleet allowed to
reach `0.0.0.0/0:443`**, and it holds the upstream provider API keys that no agent pod may hold.

---

## 1. Scope

**In scope.** The gateway Deployment, its virtual-key store (`litellm-db`, CNPG), its provider
credentials, the egress to model providers, and the trace export to Langfuse.

**Out of scope.** What the agents do with a completion (their own threat models, e.g.
`openbank-agent-service.md`), Langfuse itself, and the provider's own handling of prompts.

## 2. Data flow

```
 agent pods / admin-ui ──(TB-1: in-cluster, NetworkPolicy allow-list, port 4000)──▶ litellm
      bearer = per-agent virtual key (budgeted)                                        │
                                                                                       ├─▶ litellm-db (5432, CNPG, keys + spend)
                                                                                       ├─▶ langfuse (3000, traces)
                                                                                       └─(TB-2: cluster → internet, 443 only)─▶ model provider
```

- **TB-1** — callers are limited by `litellm-ingress-allow-list` to the agent namespaces,
  `platform` and `admin-ui`. No Ingress exposes the gateway outside the cluster.
- **TB-2** — `litellm-egress` allows DNS, Langfuse, `litellm-db` and TCP/443 to any address.
  Provider selection is by configuration (`litellm-config.yaml`), not by network policy.

## 3. Assets

| Asset | Where | Why it matters |
|---|---|---|
| Provider API keys (DeepInfra, Groq) | `litellm-secrets`, env only in this pod | spend, account takeover at the provider |
| `LITELLM_MASTER_KEY` / `LITELLM_SALT_KEY` | `litellm-secrets` | mint/decrypt virtual keys; master key bypasses budgets |
| Virtual keys + spend ledger | `litellm-db` | per-agent budget enforcement |
| Prompts and completions in flight | memory, Langfuse traces | may carry internal operational data |

## 4. STRIDE

| Category | Threat | Mitigation | Residual |
|---|---|---|---|
| **Spoofing** | A pod impersonates an agent to spend its budget | Bearer virtual key per agent; ingress allow-list by namespace | A compromised allowed namespace can use any key it can read — keys are per-agent secrets, not shared |
| **Tampering** | Modified gateway image routes prompts elsewhere | Image built in CI, digest-pinned, trivy-gated, cosign-signed; Kyverno verifies `openbank-*` signatures | Upstream supply chain of LiteLLM itself (tracked by the scan gate and version bumps) |
| **Tampering** | Config edited to add an unreviewed provider | Config is GitOps-only; a new provider is a reviewed PR; `config-revision` annotation rolls the pod | — |
| **Repudiation** | An agent denies a call that spent budget | Spend is attributed per virtual key in `litellm-db`; every call traced to Langfuse (`success_callback` and `failure_callback`) | Langfuse retention bounds the audit window |
| **Information disclosure** | Provider keys leak from an agent pod | Keys exist only in this pod's env (ADR-0175); agents hold only virtual keys | A gateway compromise exposes the keys — rotate via the ExternalSecret source |
| **Information disclosure** | Prompts leave the jurisdiction | Single egress point; provider list is reviewed config (ADR-0175) | Egress is `0.0.0.0/0:443`, so the network layer alone does not pin the destination; the control is the config review |
| **Denial of service** | Runaway agent loop exhausts provider budget | Per-model `max_budget`, per-key budgets, provider-side daily cap | Budget exhaustion is itself an outage for the AI features (fail closed, by design) |
| **Denial of service** | Gateway OOM or crash | 1536Mi limit measured against v1.99.4; readiness/liveness probes | Single replica: a crash interrupts AI features until restart |
| **Elevation of privilege** | Container escape from a pod with internet egress | `runAsNonRoot` uid 1000, `drop: [ALL]`, `allowPrivilegeEscalation: false`, seccomp `RuntimeDefault`, PodSecurity `restricted`; image build asserts the Prisma engines are usable at uid 1000 so root is never needed | — |
| **Elevation of privilege** | Master key used to mint unbudgeted keys | Master key only in `litellm-secrets`, never given to agents | Operator access to the secret equals full gateway control |

## 5. Verification

- Image: `platform-images.yml` scan + sign on every change to `openbank-infra/docker/litellm`.
- Runtime: at uid 1000 against Postgres, `/health/liveliness` answers 200 and `/key/generate`
  persists a budgeted key (recorded in the v1.99.4 rollout PR).
- Network: `litellm-egress` and `litellm-ingress-allow-list` are the whole reachability surface;
  any change to either is a review trigger for this document.
