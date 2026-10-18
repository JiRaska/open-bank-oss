# Runbook 0029 — EKS minor-version upgrade (worked example: 1.36 to 1.37)

**Owner step, not agent step.** `tofu apply` and every `aws eks update-*` call is run by the
human owner. This runbook is the ordered procedure.

**The declared version on `main` always equals the LIVE control plane.** `kubernetes_version` in
`envs/sandbox-substrate/variables.tf` is applied by every substrate `tofu apply`, so a target
version merged ahead of the window upgrades the control plane and all six add-ons the next time
anyone applies that root for any reason, skipping sections 0-3. Changing it is therefore the
first step of the window (section 4.0), not a preparatory PR.

**Why the order matters.** Karpenter validates the control-plane version at startup and
crash-loops when it is outside its compatibility range (the 1.34 incident recorded in
`envs/sandbox-platform/variables.tf`). The controller must therefore be in range of BOTH the
old and the new control plane before the control plane moves. The same logic applies to every
operator that talks to the API server (section 2).

Version source of truth: `envs/sandbox-substrate/variables.tf` (`kubernetes_version`), policed by
`aws/finops/eks-version-lifecycle.json` (ADR-0054). Managed add-ons follow automatically through
`data.aws_eks_addon_version` keyed on the cluster version, so no add-on pin is edited.

---

## 0. Pre-checks (read-only, any time before the window)

1. **Deprecated or removed APIs.** Query Prometheus (from any pod that has `PROMETHEUS_URL`):
   `apiserver_requested_deprecated_apis` and filter `removed_release!=""`. An empty result for
   the target release is the pass condition. Core `v1` Endpoints and Kyverno CRD versions show
   up as deprecated; neither is a Kubernetes removal.
2. **EKS upgrade insights.** `aws eks list-insights --cluster-name <name>` needs a recent AWS
   CLI (v2.11 does not have the subcommand). Use the console "Upgrade insights" tab, or update
   the CLI. Pass condition: no `ERROR` insight for the target version.
3. **Add-on compatibility.**
   `aws eks describe-addon-versions --kubernetes-version <target> --addon-name <x>` for
   vpc-cni, kube-proxy, coredns, eks-pod-identity-agent, eks-node-monitoring-agent,
   aws-ebs-csi-driver. Each must return at least one version.
4. **Component compatibility matrix** (section 2). Anything not listing the target must be
   upgraded first or explicitly accepted by the owner.
5. **Fresh state.** `git fetch`, then confirm `kubernetes_version` on `origin/main` still equals
   the live version (`aws eks describe-cluster --query cluster.version`) and that no other infra
   PR is open against the same files.
6. **Database health.** All CNPG clusters report `Cluster in healthy state` with 2 ready
   instances: `kubectl get clusters.postgresql.cnpg.io -A`. Do not start a window with a
   degraded cluster: a drain takes the second instance away.
7. **Backups.** Verify by listing objects at each destination path, not by reading a condition
   (see the CNPG backup bullet in the root `CLAUDE.md`).

## 1. Prerequisite PRs (separate, merged and applied BEFORE the control plane)

Each of these is its own PR with its own "Rollout safety" section.

- **Karpenter to a line that supports the target.** Upstream matrix: 1.37 needs the 1.15.x line;
  1.36 needs 1.13 or newer, so 1.15.x is safe on the current control plane too. Vendor
  `envs/sandbox-platform/karpenter-crds/<version>/` first (Helm never upgrades `crds/`), bump
  `karpenter_version`, apply, and wait for the controller to be `Ready` with no restarts.
  Prefer to wait for the first patch release of the line.
- Any other component the matrix in section 2 marks as not supporting the target.

## 2. Component compatibility (state at the time of writing, 2026-10-11)

"Not listed" means the upstream project has not yet published 1.37 in its support table; it is
not evidence of a break. Several of these tables already trail the running control plane (for
example the Kyverno table lists up to 1.35 while the cluster runs 1.36), so they are a prompt to
look, not a gate.

| Component (running) | Upstream statement for 1.37 |
|---|---|
| Karpenter 1.15.0 (declared on `main`; confirm it is applied and Ready) | Supported; 1.15.x is the first line listing 1.37. Prerequisite done once applied |
| Envoy Gateway v1.9.2 | NOT supported; only `latest` lists 1.37 (v1.9 lists 1.33-1.36) |
| CloudNativePG operator 1.30.1 | "tested, not supported" for 1.37 (supported 1.34-1.36) |
| cert-manager v1.21.2 | not listed (tested 1.33-1.36) |
| external-secrets v2.11.0 | not listed (table tops out at 1.36) |
| Strimzi 1.2.0 | not listed (tested 1.30-1.36; "might work with newer") |
| Kyverno 1.19.1 | not listed (table stops at 1.35, already behind the live 1.36) |
| prometheus-operator 0.94.1 | kube-prometheus main and release-0.19 list 1.37; mapping of 0.94 unconfirmed |
| Argo CD v3.5.3, Falco, KEDA 2.21 | not verified; check release notes before the window |

## 3. Plan review

Run this only after step 4.0 below has set the target version (on the window branch). At the
approved change window, after prerequisites and pre-checks pass, generate a fresh plan
against the current configuration and state. Keep backend state locking enabled, review this
plan, and apply only this reviewed plan; do not reuse a plan from an earlier window or revision.

```
cd openbank-infra/aws/envs/sandbox-substrate
AWS_PROFILE=openbank tofu init -input=false
AWS_PROFILE=openbank tofu plan -out=eks.plan
```

Expected change set for the version bump: `aws_eks_cluster` version, `aws_eks_node_group.bootstrap`
version, the six `aws_eks_addon` resources (version resolved at apply time), the OIDC
thumbprint, and output `cluster_version`. Zero creates, zero destroys. Anything else is drift
that predates this change (the 2026-10-11 plan also showed the config-recorder lambda,
GuardDuty runtime feature and config recorder status); apply those separately or use `-target`,
never as a side effect of the upgrade.

## 4. Apply the control plane (owner)

0. **Flip the declared version — first step of the window.** In
   `envs/sandbox-substrate/variables.tf` change `kubernetes_version` from the live version to the
   target (1.36 to 1.37 here) and update its comment. Merge it as its own PR only once the
   window is open, then run section 3's plan from that commit. If the window is abandoned,
   revert the PR before anyone else applies the substrate root.
1. Announce a window. The control-plane update is rolling and the API stays available, but
   admission webhooks and controllers see leader changes.
2. `tofu apply eks.plan`. EKS rolls the control plane first, then the managed `bootstrap` node
   group (surge replacement, respects PDBs).
3. Verify:
   - `aws eks describe-cluster --name <name> --query cluster.version` returns the target.
   - `kubectl get nodes` shows the bootstrap nodes on the new kubelet, others still on the old
     one (n-1 skew is supported).
   - `kubectl -n kube-system get pods` all Ready; Karpenter has zero new restarts.
   - Argo CD apps: no new `OutOfSync` or `Degraded`.
4. **Rollback.** EKS allows an in-place rollback to the previous minor within 7 days of the
   upgrade completing (cluster upgrade policy must permit it). Do not roll nodes onto the new
   version until the control plane has soaked.

## 5. Add-ons

They update inside the same apply (`aws_eks_addon` with `data.aws_eks_addon_version`, which
picks the default version for the new Kubernetes version). Verify with
`aws eks describe-addon --cluster-name <name> --addon-name <x> --query addon.addonVersion`
for vpc-cni, kube-proxy, coredns, eks-pod-identity-agent, eks-node-monitoring-agent,
aws-ebs-csi-driver, and that `kubectl -n kube-system get ds,deploy` is fully Ready.

## 6. Worker node waves (Karpenter drift)

The `default` and `runners` EC2NodeClasses use `al2023@latest`. Once the control plane moves,
Karpenter resolves a new AMI and EVERY Karpenter node becomes `Drifted`. That is the
2026-09-28 failure shape (fleet-wide node roll, volumes stuck detaching), so the roll is staged
by NodePool instead of left to run as one event.

Budgets today: `default` allows 1 Drifted node at a time (and none from 20:00 for 11h),
`observability` 1, `stateful` 1 node total, `runners` 10 percent. Read them live:
`kubectl get nodepools.karpenter.sh -o yaml`.

Every CNPG cluster runs 2 instances; the `*-primary` PDB has `minAvailable: 1` and
therefore 0 allowed disruptions, so Karpenter cannot voluntarily drain a node holding a
primary until the primary has moved. NodePool `terminationGracePeriod` is 1h, the forced
backstop.

Waves (hold each until the verification passes, minimum 15 minutes apart):

1. **runners / runners-warm.** CI capacity only. Verify the ARC scale sets are healthy.
2. **default (stateless services).** Let Karpenter roll at its budget of 1. Verify
   `kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded` is
   empty after each node, and the edge gateway PDB never reaches 0 allowed disruptions.
3. **observability.** Single-replica Prometheus, Loki, Tempo have `minAvailable: 1` PDBs, so
   expect a blocked drain. Roll deliberately and accept a short scrape and ingest gap, outside
   business hours.
4. **stateful (CNPG).** Last, one node at a time, never while another wave runs. Per node:
   a. list primaries on it: `kubectl get pods -A -l cnpg.io/instanceRole=primary -o wide`;
   b. switch each to its replica first (`kubectl cnpg promote <cluster> <replica-pod>`),
      money-path clusters one by one with the check below between each;
   c. only then let Karpenter drain (or delete the NodeClaim);
   d. check each affected cluster is back to `Cluster in healthy state` with 2 instances and
      replication lag near zero before the next node.
   Check EBS state before and after: no `VolumeAttachment` stuck deleting, and no pod in
   `ContainerCreating` with `AttachVolume`/`VolumeInUse` errors. If a node sticks in
   `AwaitingVolumeDetachment`, stop; do not start the next node.

Stop conditions (halt the roll, do not continue to the next node): any CNPG cluster not healthy
for more than 10 minutes, any money-path service with a failing readiness probe, a node stuck
draining for more than 30 minutes.

**Rollback for nodes.** Nodes cannot be rolled back in place. Pin the AMI by switching the
EC2NodeClass alias from `al2023@latest` to the previous version (`al2023@v<date>`) in a PR, which
makes the drifted nodes converge on the old AMI. This is only valid while the control plane is
within kubelet skew (n-3) of that AMI.

## 7. Closeout

- Refresh `aws/finops/eks-version-lifecycle.json` if AWS dates moved.
- Update the tracking issue with the observed per-wave timings and anything surprising.
- Move the N-1 recommendation forward: the lifecycle check recommends one version behind the
  newest; running the newest is allowed, it is not the policy default.
