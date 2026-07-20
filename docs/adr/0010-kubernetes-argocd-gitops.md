---
date: 2026-05-26
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kubernetes, gitops, infrastructure]
summary: "Deployment is Kubernetes 1.30+ with Helm plus Kustomize packaging reconciled by ArgoCD from Git, alongside Cilium, cert-manager and External Secrets; Knative is explicitly not adopted."
---

# 10. Kubernetes with ArgoCD GitOps deployment

## Context

OpenBank must run reproducibly across cloud providers and on-premise. Deploying via ad-hoc kubectl, Terraform-only, or vendor-specific tooling fails the cloud-agnostic test.

Industry consensus for cloud-native banking is: Kubernetes for runtime, declarative manifests in Git, a GitOps controller continuously reconciling cluster state to Git state.

## Decision

OpenBank reference deployment uses:

- **Kubernetes 1.30+** as the production runtime. Cluster size and topology are operator choice; reference manifests target 3-node minimum, multi-AZ.
- **Helm 3** for packaging — one chart per service in `openbank-infra/kubernetes/helm/<service>/`.
- **ArgoCD** as the GitOps controller. The cluster reconciles to the state declared in `openbank-infra/kubernetes/argocd/`.
- **Kustomize** layered on top of Helm for environment-specific overlays (dev, staging, prod).
- **Cilium** as CNI (eBPF networking + NetworkPolicy enforcement).
- **cert-manager** for TLS lifecycle.
- **External Secrets Operator** pulling from Vault (ADR-0007).
- **Istio** as service mesh (trial; M5 promotion target) for mTLS, traffic policy, observability.
- **Knative** is explicitly NOT adopted; revisit if scale-to-zero proves needed.

Operators MAY substitute equivalents (Linkerd for Istio, Flux for ArgoCD, etc.) per their existing standards.

## Alternatives considered

- **Ad-hoc `kubectl apply` deployment** — push manifests to the cluster by hand. Rejected because it is not reproducible and fails the cloud-agnostic test; GitOps instead keeps the cluster matching Git, makes rollback a git revert, and gives every change an audit trail with author and reviewer.
- **Terraform-only deployment** — drive workloads from Terraform without a GitOps reconciler. Rejected on the same cloud-agnostic/reproducibility grounds.
- **Vendor-specific deployment tooling** — a cloud provider's own deployment stack. Rejected because it fails the cloud-agnostic test.
- **Knative** — serverless/scale-to-zero layer on Kubernetes. Explicitly NOT adopted; the ADR leaves it to be revisited if scale-to-zero proves needed.
- **Equivalent substitutes (Linkerd for Istio, Flux for ArgoCD)** — not rejected as such: operators MAY substitute them per their existing standards, but the reference deployment picks ArgoCD and Istio.

## Consequences

**Positive**
- Reproducible deployments — cluster matches Git, always.
- Rollback is a git revert.
- Audit trail: every cluster change is a git commit with author + reviewer.
- Cloud-agnostic at the runtime layer.
- Helm + Kustomize gives flexibility without YAML duplication.

**Negative**
- Steeper learning curve than `kubectl apply`.
- Multiple layers (Helm → Kustomize → ArgoCD) require care to avoid debugging confusion.
- Service mesh adds latency and complexity; mandatory only when mTLS or fine-grained traffic policy required.

**Mitigation**
- ADR documents each layer; debugging guide in runbooks.
- Service mesh is opt-in per service initially; mandatory by M5.

## Compliance impact

- PCI DSS: not applicable — no cardholder data in scope of this ADR.
- DORA:    engaged — this is an ICT change-management and resilience control (reproducible deployment, git-revert rollback, change audit trail); specific articles not mapped in this ADR.
- GDPR:    not applicable — deployment topology, no personal data processed.
- PSD2:    not applicable — runtime and packaging choice, no payment interface.
- CNB:     not applicable — no CNB requirement is referenced in this ADR.

## References

- ArgoCD documentation
- CNCF GitOps Working Group principles
- Cilium documentation
