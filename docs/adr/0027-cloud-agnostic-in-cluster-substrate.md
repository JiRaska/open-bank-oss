# Cloud-agnostic, in-cluster OSS substrate (GitOps + OpenTofu)

Date: 2026-05-30
Status: Accepted (file reconstructed 2026-06-14 — the original `0027-*.md` was missing
from `docs/adr/` while being referenced 27× across the ADR set and the admin-ui
`/docs/cloud-architecture` overlay; this record is rebuilt faithfully from those
cross-references and the as-built sandbox. The decision itself long predates the
reconstruction and is in force.)
Author(s): Jiří Raška

## Context

OpenBank must run on a public cloud (EKS in the `openbank-sandbox` AWS account) without
becoming *dependent* on that cloud's proprietary primitives. A bank carries EU data-residency
and operational-resilience obligations (DORA), and a regulator-facing exit/portability story:
we must be able to lift the platform onto another Kubernetes substrate without rewriting the
banking services. At the same time the sandbox runs on a tight FinOps budget, so the cheapest
managed building blocks are tempting precisely where they create the deepest lock-in
(Lambda/Fargate, DynamoDB, SQS-in-the-money-path, API Gateway, Cognito).

The tension is: use the cloud for the *substrate* (compute, network, KMS, an immutable audit
sink) but keep everything *stateful and identity-bearing* on portable OSS that runs inside the
cluster.

## Decision

1. **Cloud-agnostic substrate; everything stateful runs in-cluster on OSS.** No FaaS/PaaS in the
   request or money path. The only AWS primitives allowed in the path are the substrate itself —
   EKS, VPC networking, KMS (envelope encryption / auto-unseal), and the account-level audit sink.
   Postgres (CNPG), Kafka (Strimzi), the schema registry (Apicurio, **SQL storage, never
   in-memory**), identity (Keycloak), secrets (Vault → OpenBao + External Secrets Operator), and
   object cache (Redis/Valkey) all run as in-cluster operators. Kubernetes is the platform
   registry; no AWS-only service becomes a hard dependency.

2. **IaC = OpenTofu; deploy = ArgoCD GitOps.** OpenTofu (remote S3 state,
   `openbank-tofu-state-…`) provisions the substrate root (VPC across 3 AZ, single NAT for
   FinOps, EKS control plane, KMS CMK, IAM/IRSA + EKS Pod Identity, Karpenter IAM + SQS
   interruption queue). Everything above the bootstrap node group is owned by a single ArgoCD
   **app-of-apps** — merged-to-`main` *is* deployed. No `kubectl apply` by hand; no drift outside
   Git. (ADR-0010 establishes the GitOps engine; this ADR fixes *what may live where*.)

3. **Compute is arm64 Graviton, Spot-first, Karpenter-autoscaled.** A small managed bootstrap
   node group (t4g) carries system pods + the Karpenter controller + ArgoCD; Karpenter provisions
   the rest (arm64 only, Spot-first, aggressive consolidation) via EC2NodeClass/NodePool.

4. **Isolated VPC, egress-only posture; EU residency.** Private subnets, S3 gateway + interface
   VPC endpoints, single NAT egress. The VPC-internal invariant is load-bearing: no component may
   introduce a cloud SMS/voice/email dependency that punches a hole in it (paging, notifications,
   etc. stay in-cluster or egress-only — see ADR-0088 ntfy, ADR-0059 webhooks).

5. **Two FinOps tiers.** **Tier 1 (dev/sandbox, now):** the in-cluster OSS stack above. **Tier 2
   (production-only):** capabilities added *only when the go-live conditions below are met* — never
   speculatively, and never by reaching for a proprietary managed service that breaks (1).

## Go-live conditions (the regulatory gate)

These are the conditions that flip the platform from sandbox to a production-eligible posture.
Each is enforced elsewhere as an `advisory → enforced` gate (ADR-0029 governance-as-code); this
ADR is their origin:

1. **Durable, immutable account audit.** CloudTrail + AWS Config (immutable account-level audit +
   drift detection) — DORA Art. 12. *(As-built: planned; not yet in IaC.)*
2. **No in-memory state for regulated stores.** e.g. Apicurio on SQL storage, not in-memory.
   *(As-built: satisfied.)*
3. **Backups + restore proven** for every stateful store (CNPG, object state) to S3 — no live
   `pg_upgrade`/cutover without a proven restore (see runbook 0003).
4. **Secrets never in IaC state**, KMS-unsealed, ESO-synced (see ADR-0017 / runbook 0005).
5. **Default-deny network posture** between namespaces (NetworkPolicy baseline, ADR-0081).

## Consequences

- **Portability preserved.** No FaaS lock-in; the banking services are plain Quarkus containers on
  Kubernetes. Autoscalers (KEDA/Knative, ADR-0041/0057/0083) are added as *operators on this
  cluster*, not as a different cloud runtime — they don't violate this ADR.
- **As-built deltas (honest record).** Two original choices were superseded in implementation and
  are noted here so the ADR matches reality: the gateway is **nginx ingress**, not the originally
  considered **Kong**; and the CNI today is **VPC-CNI**, with **Cilium default-deny** as the
  target (ADR-0081 supplies the NetworkPolicy baseline in the interim). The EKS control plane has
  moved from the original **1.31** pin (PR #84) to **1.35**.
- **FinOps discipline costs latency sometimes** (single NAT, Spot consolidation, scale-to-zero
  tiers). Accepted for sandbox; Tier-2 conditions relax specific pieces for production.
- **Anything proposing an AWS-only primitive in the path is rejected by default** and must cite a
  go-live condition that justifies it. This ADR is the thing those rejections point at.

## Related

ADR-0008 (OpenTelemetry), ADR-0010 (Kubernetes + ArgoCD GitOps), ADR-0017 (Vault/OpenBao secrets),
ADR-0029 (governance-as-code gates), ADR-0030 (supply-chain / admission signature verification),
ADR-0041 / ADR-0057 / ADR-0083 (scale-to-zero tiers as in-cluster operators), ADR-0054 (FinOps
lifecycle), ADR-0081 (cluster segmentation / default-deny baseline), ADR-0088 (egress-only paging).
