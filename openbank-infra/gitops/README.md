# gitops/ — ArgoCD app-of-apps for the sandbox EKS cluster

ArgoCD is seeded by OpenTofu (`aws/envs/sandbox-platform`); from there it owns
all in-cluster state via this app-of-apps (ADR-0027). No direct `kubectl`
writes — change a manifest here, merge to `main`, ArgoCD reconciles.

```
gitops/
  bootstrap/root-app.yaml   # the seed Application (app-of-apps) — applied once
  apps/                     # one ArgoCD Application per component
    apicurio.yaml
  components/               # the actual k8s manifests each app owns
    apicurio/               # schema registry (SQL) + its CNPG Postgres
```

Namespacing follows ADR-0037 (domain = namespace; `messaging` is the infra
plane for the event bus and its schema registry).

## Bootstrap (one-time)

ArgoCD reaches this private repo with a read-only SSH deploy key (secret
`openbank-repo` in the `argocd` namespace; the private key lives only in the
cluster, never in tofu state or git). Once that secret exists, seed the
app-of-apps:

```
kubectl apply -f openbank-infra/gitops/bootstrap/root-app.yaml
```

`root` then syncs everything under `apps/`. All Applications track `main`.

## Adding a component

1. Add manifests under `components/<name>/`.
2. Add an `apps/<name>.yaml` Application pointing at that path.
3. Merge to `main`; `root` picks it up automatically.
