# FINOS Common Cloud Controls (CCC) + AI Governance Framework (AIGF) mapping

Date: 2026-07-23
Frameworks: FINOS Common Cloud Controls (CCC); FINOS AI Governance Framework (AIGF)
Owner: CTO / Maintainer Lead
Status: living document — control statuses track `origin/main`

---

## Purpose

Map this platform's implemented controls to the FINOS **Common Cloud Controls (CCC)** control families
and the FINOS **AI Governance Framework (AIGF)**. Each row is
**control family → our implementation (with the artifact that proves it) → status**. Status is one of:

- **implemented** — the control exists and is enforced/checkable on `origin/main`.
- **partial** — the control exists but is advisory-only, single-tier, or scoped narrower than the family.
- **gap** — not yet built; called out honestly so the mapping is not misread as complete.

The authoritative, CI-enforced source for most of these controls is
[`openbank-libs/governance/rules.yaml`](../../openbank-libs/governance/rules.yaml); this document is a
human-readable crosswalk, not a second source of truth.

---

## Part A — Common Cloud Controls (CCC)

| CCC control family | Our implementation | Evidence artifact | Status |
|---|---|---|---|
| Identity & access management | Keycloak OIDC / OAuth2 for customer + operator auth; unified OPA authorization sidecar serving both REST and MCP tool-calls | ADR-0034; `AuthorizeInterceptor`; per-service Rego bundles from openbank-libs | partial — OPA enforce is staged; several services still advisory or lack a live PDP sidecar (issue #1797) |
| Authorization / least privilege | Rego policies with principal-type vocabulary (`ANONYMOUS`/`AI_AGENT`/`HUMAN`); money-path action prefixes; four-eyes on money-path | ADR-0034; `rules.yaml: money_path_services`, `four_eyes`, `money_path_action_prefixes`; `check-no-service-principal-type.sh` | implemented |
| Data encryption in transit | mTLS on Kafka (topic-scoped Strimzi tls listener + per-service KafkaUsers/ACLs); cert-manager PKI; TLS ingress | ADR-0137; External Secrets cert projection | partial — ADR-0137 enforces the `payment.scheme-accepted` boundary; the cluster-global allow-everyone gate stays on deliberately |
| Data encryption at rest / secrets | OpenBao runtime secret injection; break-glass keys in AWS Secrets Manager; no secrets in-cluster at rest | `rules.yaml`; `incident-response.md` §5 key ceremonies | implemented |
| Network security / segmentation | Kubernetes NetworkPolicies for east-west isolation per service | `dora-ictrm.md` (Availability/Confidentiality controls); gitops NetworkPolicies | partial — edge coverage gaps found by walking live paths (e.g. party 500 on missing edge) |
| Logging & audit | Tamper-evident SHA-256 hash-chain audit trail with a verify endpoint; AI-attributed audit events | ADR-0086; ADR-0133; audit-service `AuditResource`/`AuditRepository` | implemented |
| Monitoring & detection | Grafana + Pyrra SLOs; GoAlert on-call; Falco runtime; HolmesGPT RCA agent; GlitchTip errors | ADR-0088; ADR-0091; `dora-ictrm.md` Detection | implemented |
| Configuration & change management | Governance-as-code: enforced conventional commits, per-service SemVer, release-please, generated catalog; GitOps via ArgoCD | ADR-0029; `rules.yaml`; release-please config | implemented |
| Vulnerability & patch management | PR-time dependency-review CVE gate; per-service + aggregate CycloneDX SBOM; image rescan; OpenVEX triage | `dependency-submission.yml` + `dependency-review.yml`; `security.yml`; `image-rescan.yml`; `vex-triage.yml` | implemented |
| Supply-chain integrity / provenance | Cosign image signing + KMS; CycloneDX SBOM attestation verified by Kyverno; SLSA provenance + signed release evidence bundle; fleet-attestation gate | ADR-0029/0030; `cosign-attest.sh`; `verify-sbom-attestation-policy.yaml`; `fleet-attestation.yml`; `verify-release-evidence.yml` | partial — SBOM-attestation Kyverno policy is Audit, not yet Enforce (`rules.yaml: provenance.gate`) |
| Admission control / policy enforcement | Kyverno ClusterPolicies (image signature verify Enforce; SBOM attestation Audit); OPA bundles regenerated fleet-wide on any rules/rego change | `verify-images-policy.yaml`; `verify-sbom-attestation-policy.yaml`; `opa-policy.yml` | partial — signature Enforce, SBOM attestation Audit |
| Resilience, backup & recovery | CNPG continuous S3 WAL archiving + 14-day retention + PITR; ArgoCD rollback; RTO/RPO tiers T0–T3; automated DR restore-verify skeleton | `bcp-policy.md`; `automated-dr-restore.md`; `dr-restore-verify.yml` | partial — backups implemented; automated restore-verify not yet wired (hard-fails by design) |
| Incident management | Four-tier P1–P4 severity model; declaration timing; register tied to the audit chain; DORA Art. 19–20 reporting | ADR-0146; `incident-response.md` | partial — no dedicated persistent incident-register store; bus factor 1 (no secondary on-call) |
| Third-party / ICT dependency risk | ICT third-party register of record with criticality + exit position per provider | ADR-0174; `dora-ictrm.md` Art. 28/30 backlog | partial — register exists; vendor risk register + Art. 30 clauses are backlog |

---

## Part B — AI Governance Framework (AIGF)

Scope: the four AI agent services (agent-service, devops-agent, finops-agent, and the read-only
observability/governance agents) governed under ADR-0031 / ADR-0136.

| AIGF control area | Our implementation | Evidence artifact | Status |
|---|---|---|---|
| AI system inventory / agents-as-code | Agents declared as least-privilege workloads in `agents.yaml`, passing the same PR/CI/OPA gates as humans; Markdown charters with an id-parity gate | ADR-0031; ADR-0156; agent-charter-registry.yml | implemented |
| Human oversight (model-proposes / bank-disposes) | Every agent output is a proposal, never auto-remediation; HolmesGPT read-only + on-demand only; DevOps/FinOps agents propose PRs a human must approve | ADR-0091; ADR-0119; ADR-0112; ADR-0102 | implemented |
| Access control for AI tool-use | Policy-gated MCP `tools/call` through the same OPA sidecar as REST; principal type `AI_AGENT` distinct from `HUMAN` | ADR-0034; `authz-policy-auditor` (ADR-0167) | partial — OPA enforce staged; agent-service blanket-ROLE_OPERATOR priv-esc tracked as a draft advisory |
| Attribution / auditability of AI actions | AI-attributed entries on the tamper-evident audit chain; agent charter identity threads into incident declarations | ADR-0031 D5; ADR-0133; `incident-response.md` §3 | implemented |
| Model governance / lifecycle | ML decisioning platform: event-fed feature store, in-process ONNX serving, champion/challenger shadow-to-promote; deterministic rule layer kept as a permanent floor | ADR-0139 | partial — feature store + shadow shipped; ONNX serving adapter still a placeholder |
| Independent review of AI-authored changes | **Restored 2026-08-09, NARROWED (ADR-0251).** The predecessor (ADR-0154) was withdrawn the same day: GitHub Models was retired 2026-07-30 and answers HTTP 410, and the Claude fallback was gated on `retired != 'true'` — disabled precisely when the primary failed — with both the review step and its own verification step `skipped` in 10/10 runs, every run concluding `success`. The replacement reviews **money-path services, the `security` type and the `governance` scope only** — 114 of 804 PRs merged in the preceding 7 days (~16.3/day), not the fleet — driven by the `claude` CLI, advisory, never a required check. The structural fix: the proof-of-review check (`check-agent-review-happened.sh`, gate `agent-review-proof-falsifiable`) runs `if: always()`, shares no condition with the review, and reads the model's reply text rather than any exit code. The "defer to humans" half remains unbacked: `main-protection` still has `required_approving_review_count: 0` (#2183). | ADR-0251; ADR-0154 (deprecated); #2161; #4281; #2183 | **partial — money-path and governance scope only, not fleet-wide** |
| Policy-liveness / control assurance for AI | Read-only sentinel/auditor agents (control-liveness, governance-auditor, docs-truth, authz-policy, release-steward, flaky-test) re-check the fleet's own controls | ADR-0163–0168 | partial — most are advisory / not-yet-scheduled (`accepted/partial`) |
| Third-party AI dependency posture | ADR-0174 states plainly that ADR-0031's LiteLLM/vLLM/Anthropic gateway topology is not deployed | ADR-0174 | gap — documented, not built |

---

## Honest gaps (summary)

- **OPA enforce is not fleet-wide.** Several services run advisory or lack a live PDP sidecar
  (issue #1797); enabling enforce without a sidecar fails closed (422). Do not read "OPA authz" as
  universally enforcing.
- **SBOM-attestation admission is Audit, not Enforce** (`rules.yaml: provenance.gate`).
- **No persistent incident-register store; bus factor 1** — see `incident-response.md`.
- **ML serving adapter is a placeholder** (ADR-0139 `partial`).
- **AI gateway topology (ADR-0031) is not deployed** (ADR-0174).
- **Automated DR restore-verify is a skeleton** that hard-fails rather than reporting a hollow pass.

---

## Related

- [`docs/compliance/evidence-pack.md`](./evidence-pack.md) — DORA + supply-chain evidence pack with
  auditor-runnable verification commands.
- [`openbank-libs/governance/rules.yaml`](../../openbank-libs/governance/rules.yaml) — authoritative
  CI-enforced controls.
- [`docs/threat-models/`](../threat-models/) — per-service threat models.
