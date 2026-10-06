# ArgoCD application health alerts

This runbook covers `ArgoCDAppDegraded` and
`ArgoCDAppHealthUnknown`. Persistent `OutOfSync` with auto-sync has
its own [drift diagnosis](0026-stuck-node-and-argocd-drift.md).

1. Record the application, health state, sync state, first observed time,
   and the unhealthy resource shown in the ArgoCD resource tree.
2. If the app is `Synced` and `Degraded`, inspect the resource's
   conditions and recent events. The desired manifest was applied; a
   workload or dependency may still be unhealthy.
3. If health is `Unknown`, check whether a resource type or health
   assessment is missing. Do not count `Unknown` as healthy.
4. Compare the affected app with other apps and recent deploys. Give the
   owning team the resource condition and event evidence, then verify that
   the app returns to a healthy state.

These observations do not authorize a sync override or a workload restart.
