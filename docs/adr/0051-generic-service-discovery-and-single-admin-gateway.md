# Generic service discovery and a single north-south gateway for the admin plane

Date: 2026-06-01
Status: Accepted
Delivery-Status: Partial
Author(s): Platform

## Context

The admin UI currently reaches the ~30 `openbank-*` services through three **hardcoded**
registries baked into the web tier:

- `openbank-admin-ui/src/lib/api.ts` — a 28-entry `SERVICES` list (name + port).
- `src/app/api/svc/[service]/[...path]/route.ts` — a 28-entry `SERVICE_MAP` (name → container + port)
  used by the generic BFF proxy.
- `src/app/api/services/health/route.ts` — a 28-entry `SERVICES` array (name + port + container +
  per-service business `healthPath`) used by the System Health / Tech Inventory screens.

This has three problems:

1. **It does not scale.** Every new service, port change, or rename requires hand-editing the web
   tier in three places. The lists already drift from reality (ports, container names, which services
   actually exist). New services do *not* appear automatically.
2. **The health view is wrong.** It reports "0 healthy" even though `account`, `balance`, `ledger`
   are live. Two root causes: (a) the admin-ui Deployment has no backend-host configuration, so the
   BFF probes `localhost` (its own pod); (b) more fundamentally, Quarkus serves `/q/health/*` on the
   **management port (8085)**, which the per-service `Service` does **not** expose — only the business
   port (8100–8127) is. So no amount of probing `/q/health/ready` *through the Service* can work; the
   kubelet reaches it on the pod directly. Probing business endpoints instead requires auth + valid
   UUIDs + DB state and returns 4xx on a healthy-but-empty service.
3. **Direct web→service fan-out is the wrong topology.** The browser-facing tier holds the full
   service map and reaches every backend directly. There is no single north-south entry point to
   attach authn/z, rate-limiting, and observability to.

We want a *generic, modern, scalable, modular* design where the service list is **not fixed in the
web tier** and new services light up automatically.

## Decision

We will split the problem into a **control plane** (what services exist and are they healthy) and a
**data plane** (how the admin UI calls a service's API), and solve each with the cluster's own source
of truth instead of a hardcoded list.

**1. Control plane — Kubernetes API service discovery (the inventory + health source of truth).**
The admin-ui BFF gains a discovery route (`/api/services/discovery`) that queries the Kubernetes API
for workloads carrying the OpenBank component label and derives the inventory and health from cluster
state:

- **Membership** = `Deployment`/`Service` objects selected by a label
  (`app.kubernetes.io/part-of in {accounts, app, …}`, plus an explicit opt-in label
  `openbank.tech/service=true` going forward). New services that ship their manifest with the label
  appear with zero web-tier changes.
- **Health** = the `Deployment` readiness (`.status.readyReplicas` vs `.spec.replicas`, and the
  `Available` condition). This *is* the kubelet's own `/q/health/ready` result on the management port,
  re-published by the control plane — authoritative, auth-free, state-free, and already computed.
- **Identity/version** = best-effort `GET /api/v1/info` (served by `openbank-libs` on the business
  port) for the tech-stack/version chips, exactly as today, but only for services discovery says exist.

The admin-ui Pod runs as a **least-privilege ServiceAccount** with a read-only `ClusterRole`
(`get`/`list`/`watch` on `services`, `deployments`, `endpoints` — no secrets, no writes). Crucially
that role is **bound per-namespace via one `RoleBinding` in each OpenBank domain namespace, NOT via a
cluster-wide `ClusterRoleBinding`** — so the RBAC layer, not just the `OPENBANK_NAMESPACES` app filter,
is the authoritative boundary: a compromised admin-ui SA still cannot enumerate workloads outside its
six domains. The discovery code therefore lists each namespace explicitly
(`/apis/apps/v1/namespaces/<ns>/deployments`) rather than issuing a cluster-scoped collection request.
In local dev (no in-cluster token) the route falls back to the existing static list so nothing breaks
off-cluster.

**2. Data plane — Kong Ingress Controller as the single north-south gateway.**
Each service declares **its own route** via a Kubernetes `Ingress` in its own gitops component, under
one gateway host (`api.open-bank.tech`) with a path prefix (`/<service>/…`). The Kong Ingress
Controller auto-discovers routes by watching `Ingress` resources — there is no central route table to
maintain, and a new service is reachable the moment its manifest is applied. The admin-ui BFF proxy
collapses to a single upstream base URL (the in-cluster Kong gateway `Service`) plus the path prefix;
the 28-entry `SERVICE_MAP` is deleted. The gateway becomes the place to attach JWT validation,
rate-limiting, and request tracing once, rather than per BFF route.

We will roll this out in phases so each step is independently shippable:

- **Phase 1 (now):** K8s-API discovery for the inventory + System Health screens; de-hardcode and fix
  "0 healthy". admin-ui ServiceAccount + RBAC + discovery route; static list demoted to dev fallback.
- **Phase 2:** Deploy the Kong Ingress Controller; each service ships its `Ingress` route; BFF proxy
  resolves the upstream from discovery (namespace-aware `Service` DNS) instead of `SERVICE_MAP`.
- **Phase 3:** Point the BFF at the single Kong base URL; delete the remaining hardcoded maps; attach
  gateway-level authn/z + rate-limiting.

## Alternatives considered

- **Keep the hardcoded lists, just centralise them in one module.** Removes the triple-duplication but
  still requires a human edit per service and still can't reflect live health. Rejected: not dynamic.
- **A bespoke service-registry service (services self-register via heartbeat).** More moving parts and
  a new money-adjacent stateful component to operate, duplicating what Kubernetes already tracks
  (readiness, endpoints). Rejected: reinvents the platform's own registry.
- **Probe `/q/health/ready` through each Service from the BFF.** Requires exposing the management port
  (8085) on every `Service` (wider attack surface, per-service manifest churn) and still leaves the
  inventory list hardcoded. Rejected: the K8s API already has the readiness signal.
- **Service mesh (Istio/Linkerd) for north-south.** Heavyweight for a single admin entry point;
  mesh value is east-west mTLS, which is a separate, later decision. Rejected for this scope.
- **Consul / external service discovery.** Another stateful system to run alongside Kubernetes, which
  is already the registry. Rejected: not cloud-substrate-aligned (ADR-0027).

## Consequences

**Positive**
- New services appear in the admin plane automatically by shipping a labelled manifest — nothing to
  edit in the web tier. Truly scalable and modular.
- System Health reflects real cluster state (the kubelet's readiness), fixing the "0 healthy" defect
  correctly rather than papering over status codes.
- One north-south gateway gives a single place for authn/z, rate-limiting, and tracing; the browser
  tier no longer holds the full service map.
- Least-privilege read-only RBAC; no secrets in the web tier.

**Negative**
- admin-ui gains a (read-only) Kubernetes API dependency and RBAC surface to review.
- Two sources of truth during the transition (discovery + static fallback) until Phase 3 removes the
  static maps.
- Kong becomes a critical north-south component to operate (HA, upgrades) once Phase 2 lands.

**Neutral**
- Adding a domain namespace is a reviewed two-line change — a new per-namespace `RoleBinding` plus an
  `OPENBANK_NAMESPACES` bump — which is an intentional governance checkpoint enforced at the RBAC layer,
  not merely in app config.

## Compliance impact

- PCI DSS: positive — a single north-south gateway is the right enforcement point for segmentation and
  access logging (Req 1, 10). No cardholder data in scope here.
- DORA:    positive — authoritative health/inventory improves operational resilience monitoring; RBAC
  is least-privilege.
- GDPR:    not applicable — discovery reads workload metadata only, no personal data.
- PSD2:    not applicable to the admin plane; the gateway is the future place to host PSD2 edge controls.
- CNB:     not applicable directly; improves operational transparency expected of a regulated operator.

## References

- ADR-0027 — cloud-agnostic substrate (Kubernetes as the platform registry).
- ADR-0029 — governance-as-code (derive from code / enforce in CI / show in UI).
- ADR-0002 — hexagonal architecture per service.
- `openbank-libs` `web/ServiceInfoResource` — `/api/v1/info` stack snapshot.

## Implementation history

- **Phase 1 implemented (2026-06-24):** `/api/services/discovery` BFF route added
  (`openbank-admin-ui/src/app/api/services/discovery/route.ts`) — queries the Kubernetes API for
  Deployments in OpenBank domain namespaces and returns `{ source: "k8s", services: [...] }` with
  `ready`/`desired`/`healthy` per service. Falls back to `{ source: "static", services: [] }` in dev
  (no in-cluster SA token). ServiceAccount + ClusterRole `openbank-discovery-reader` + per-namespace
  RoleBindings extracted to
  `openbank-infra/gitops/components/admin-ui/rbac.yaml` (7 namespaces: admin, accounts, payments,
  kyc-onboarding, risk-compliance, ledger, notifications). The System Health screen consumes the
  discovery feed via `discoverServices()` in `@/lib/discovery`; health route falls back to static
  probing off-cluster.
- **Phase 2 (Kong gateway):** deferred.
