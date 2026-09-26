# EKS minor-version upgrade

Change `kubernetes_version` in this root and review a full substrate plan before
using the existing `substrate-tofu.yml` apply workflow on the merged revision.
A successful lifecycle gate or plan is not evidence of a completed upgrade.

## Before applying

1. Check EKS upgrade insights for the target minor and read the target release
   notes. Resolve health, removed-API, admission-webhook and version-skew findings.
   Confirm Karpenter and the installed controllers support the target version.
2. Verify current backups and restore evidence for stateful workloads. Check node
   health, disruption budgets, spare capacity and the scaling schedule; avoid a
   scale-down window. Check admission attestations for images that will restart.
3. Run `tofu init`, `tofu validate` and a full `tofu plan -out=<private-plan>` from
   this root. Keep state, plans and account-specific evidence outside the repository.
   Inspect every change, including unrelated drift. Resolve it before applying;
   a targeted plan is not proof that the full workflow is safe.
4. Verify that the control plane and managed bootstrap group both move to the
   intended minor in place. The managed group must retain `max_unavailable = 1`.
   All add-on versions must be concrete and compatible with the target, with no
   unexpected downgrade. A node-group no-op while the control plane upgrades is
   incomplete: control-plane updates do not automatically upgrade workers.

## Rollout and evidence

The module orders the control plane before VPC CNI and kube-proxy, then the
managed bootstrap workers, then CoreDNS, Pod Identity and EBS CSI. Updating
kube-proxy one minor ahead of the remaining old kubelets is within the upstream
[version-skew policy](https://kubernetes.io/releases/version-skew-policy/).
Karpenter-managed nodes are separate from the bootstrap group: verify their
actual kubelet versions and controlled replacement through their NodePools too.

After the reviewed apply, require completed EKS updates, ACTIVE cluster/add-ons,
no reported health issues, Ready nodes at the intended version and no stuck
drains or pending workloads. Check DNS, pod networking, volume attachment,
identity, ArgoCD sync errors and a synthetic settlement/ledger/balance flow.
Record the deployed revision and observed versions with the private execution
evidence; do not infer them from the source defaults.

## Failure handling

EKS control-plane minor upgrades cannot be downgraded. Reverting the version
input after a completed upgrade is not rollback. Stop further changes, diagnose
the failed update and recover forward; replacing the cluster requires a separately
reviewed restore procedure. Do not force node eviction past disruption budgets.
Reverting an add-on requires verified compatibility and a new reviewed plan.

Follow the [AWS EKS update procedure](https://docs.aws.amazon.com/eks/latest/userguide/update-cluster.html)
for the selected version. This document specifies acceptance conditions; it does
not attest that a particular environment has met them.
