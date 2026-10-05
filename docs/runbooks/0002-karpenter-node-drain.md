# Runbook 0002 — Karpenter capacity, churn, and Argo CD health

This runbook is linked by `FinOpsKarpenterNodeChurnHigh`,
`FinOpsKarpenterChurnOutsideMaintenance`, `FinOpsKarpenterNodePoolNearCap`,
`ArgoCDAppDegraded`, and `ArgoCDAppHealthUnknown`. These checks are diagnostic;
they do not change nodes, workloads, or applications. For pods that cannot start
on an otherwise Ready node, use [runbook 0026](0026-stuck-node-and-argocd-drift.md).

## Karpenter churn

1. Read the alert's `nodepool` label and compare the current
   `karpenter_nodes_terminated_total{nodepool!~"runners.*"}` increase with the
   previous hours. `FinOpsKarpenterNodeChurnHigh` uses a one-hour increase;
   `FinOpsKarpenterChurnOutsideMaintenance` uses 30 minutes during 07:00–20:00 UTC.
2. Inspect recent NodeClaims and their conditions, then the affected pool's
   disruption settings. Correlate terminations with pending pods and application
   readiness before attributing cost or availability impact.

   ```bash
   kubectl get nodeclaims -o wide
   kubectl get nodepools -o wide
   kubectl get pods -A --field-selector=status.phase=Pending
   ```

3. If the node also has `NodePodSandboxCreationFailing`, `NodePodsStuckStarting`,
   or `NodeDegradedWhileReady`, follow [runbook 0026](0026-stuck-node-and-argocd-drift.md)
   to distinguish a faulty node from normal consolidation.

## NodePool near its CPU cap

`FinOpsKarpenterNodePoolNearCap` compares
`karpenter_nodepools_usage{resource_type="cpu"}` with
`karpenter_nodepools_limit{resource_type="cpu"}`. Confirm both series exist for
the alert's `nodepool`, then inspect its declared limits and queued demand:

```bash
kubectl get nodepool <nodepool> -o yaml
kubectl get pods -A --field-selector=status.phase=Pending
```

A high ratio alone does not say whether the cap or workload requests are wrong.
Record the pending pods' scheduling events and compare them with recent changes
to requests and NodePool limits before planning a change.

## Argo CD application health

`ArgoCDAppDegraded` reads `argocd_app_info{health_status="Degraded"}`;
`ArgoCDAppHealthUnknown` reads `health_status="Unknown"`. Locate the named
Application and its unhealthy or unassessed resources:

```bash
kubectl -n argocd get application <app> -o jsonpath='{.status.health.status}{"\n"}{.status.sync.status}{"\n"}'
kubectl -n argocd describe application <app>
```

`Synced` does not imply `Healthy`: inspect the resource tree and the workload's
own health signal. `Unknown` means Argo CD could not assess health; check for a
missing CRD or health check. Persistent `OutOfSync` with auto-sync uses
[runbook 0026](0026-stuck-node-and-argocd-drift.md), not this health diagnosis.
