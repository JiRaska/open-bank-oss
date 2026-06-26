# OpenBank Kubernetes manifests

Plain Kubernetes manifests for the OpenBank application tier, Kong API gateway, ingress entrypoints, and a CloudNativePG baseline cluster.

## Structure

```text
k8s/
  base/            reusable manifests
  overlays/local/  kind/minikube-friendly hosts
```

## Included resources

- `Namespace` for `openbank`
- Shared `ConfigMap` and placeholder `Secret` objects for common Quarkus runtime settings
- CloudNativePG `Cluster` baseline with bootstrap user secret and per-service database creation statements
- `Deployment`, `Service`, and `HorizontalPodAutoscaler` for:
  - account-service
  - ledger-service
  - transaction-service
  - balance-service
  - consent-service
  - psd2-service
  - tpp-registry-service
  - sca-service
  - agent-service
  - party-service
  - notification-service
  - audit-service
  - kyc-service
  - sepa-service
  - domestic-service
  - aml-service
  - openbank-admin-ui
  - openbank-api-gateway
- `Ingress` resources for API and admin UI access

## Dependency assumptions

These manifests intentionally stay vendor-light. They assume the following dependencies are available in-cluster or reachable via the configured DNS names:

- CloudNativePG operator installed before applying `base/cnpg.yaml`
- Kafka reachable as `kafka.openbank.svc.cluster.local:9092`
- Keycloak reachable as `keycloak.openbank.svc.cluster.local:8080`
- Valkey reachable as `valkey.openbank.svc.cluster.local:6379`
- MailHog reachable as `mailhog.openbank.svc.cluster.local:1025`
- OTLP collector reachable as `otel-collector.observability.svc.cluster.local:4317`
- Metrics Server installed if HPAs should reconcile
- NGINX Ingress Controller installed if ingress should become routable

If your cluster uses different service names, patch `base/shared.yaml` or create another overlay.

## Image strategy

Application deployments use explicit local-friendly image names such as `openbank-account-service:dev` and `openbank-sepa-payment:dev`.

That keeps manifests independent from a specific registry while still matching the current repository layout and Dockerfiles. In CI or higher environments, override image registry and tag with Kustomize image substitutions.

Example:

```bash
kubectl kustomize k8s/overlays/local > /tmp/openbank.yaml
```

Or with standalone Kustomize:

```bash
kustomize edit set image openbank-account-service=ghcr.io/acme/openbank-account-service:1.0.0
```

## Apply order

1. Install prerequisites:
   - CloudNativePG operator
   - Metrics Server
   - Ingress controller
2. Review and replace placeholder secrets in `base/shared.yaml`.
3. Render and review the local overlay:

```bash
kubectl kustomize k8s/overlays/local
```

4. Apply the local overlay:

```bash
kubectl apply -k k8s/overlays/local
```

5. Wait for the database cluster and deployments:

```bash
kubectl get pods -n openbank
kubectl get hpa -n openbank
```

## Local kind/minikube flow

The local overlay uses `api.openbank.localtest.me` and `admin.openbank.localtest.me`, which resolve to `127.0.0.1` without editing `/etc/hosts`.

Suggested flow:

```bash
# 1. Create a local cluster
kind create cluster --name openbank

# 2. Install prerequisites
kubectl apply -f https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/main/releases/cnpg-1.24.0.yaml
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

# 3. Build or retag images to the names expected by the manifests
#    Example: docker build -t openbank-account-service:dev ../openbank-account-service

# 4. Load images into kind if needed
kind load docker-image openbank-account-service:dev --name openbank

# 5. Validate and apply manifests
kubectl kustomize k8s/overlays/local > /tmp/openbank-local.yaml
kubectl apply -k k8s/overlays/local
```

For Minikube, replace `kind load docker-image ...` with `eval $(minikube docker-env)` before building images or push to a registry reachable by Minikube.

## Validation commands

```bash
kubectl kustomize k8s/base > /tmp/openbank-base.yaml
kubectl kustomize k8s/overlays/local > /tmp/openbank-local.yaml
```

## Access

- API gateway: `http://api.openbank.localtest.me`
- Admin UI: `http://admin.openbank.localtest.me`

Keycloak ingress is intentionally left out of this manifest set. For local authentication tests, expose Keycloak separately or patch the admin UI config to match your identity endpoint.
