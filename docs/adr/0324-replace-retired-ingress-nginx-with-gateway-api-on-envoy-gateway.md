---
date: 2026-09-28
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [networking, kubernetes, security, gitops]
summary: "ingress-nginx is archived upstream with no further fixes; the edge moves to Gateway API served by Envoy Gateway behind the same single NLB, host by host, and ingress-nginx is removed at the end."
---

# ADR-0324 — Replace retired ingress-nginx with Gateway API on Envoy Gateway

## Context

ADR-0027 chose a cloud-agnostic, in-cluster edge and ADR-0010 listed the GitOps
platform components. `openbank-infra/gitops/apps/ingress-nginx.yaml` records the
concrete choice as a comment: ingress-nginx rather than the AWS ALB controller, with an
NLB as the only AWS coupling. ADR-0234 built the internal tool gate on the nginx
`auth-url`/`auth_request` mechanism. None of these ADRs decides what replaces the
controller. This ADR is needed because the controller itself has reached end of life.
The `kubernetes/ingress-nginx` repository is archived (`gh api repos/kubernetes/ingress-nginx`
reports `archived: true`). Best-effort maintenance ended in March 2026, so chart 4.15.1 /
controller 1.15.1 will not receive further security fixes.

What the edge actually does today, measured on `origin/main` on 2026-09-28:

- **Entry.** One controller replica behind one internet-facing NLB. It uses the in-tree
  Service annotation and `externalTrafficPolicy: Cluster` (since changed to `Local`). It is
  open to the internet: the sandbox edge carries no source-IP allow-list (owner decision
  2026-09-29). TLS is issued by cert-manager (14 manifests carry
  `cert-manager.io/cluster-issuer`) over DNS-01, which needs no inbound path
  (`aws/envs/sandbox-substrate/main.tf`). external-dns is `v0.15.0` with
  `--source=ingress` only.
- **Scope.** 14 manifests set `ingressClassName: nginx`, and `kube-prometheus-stack`
  renders one more with `auth-url`. The full list is in #11258.
- **Annotations.** These are in use: `ssl-redirect` (18), `configuration-snippet` (11),
  `auth-url` (9), `limit-rps`/`limit-burst-multiplier` (8), `limit-connections` (5),
  `upstream-vhost` (3), `auth-signin` (3), `server-snippet` (2), `proxy-body-size` (2),
  `permanent-redirect` (2), and one each of `use-regex`, `proxy-set-headers`,
  `proxy-{send,read}-timeout`, `proxy-buffering`, `auth-response-headers`,
  `enable-modsecurity`, `enable-owasp-core-rules` and `modsecurity-snippet`.
  The repo has no `backend-protocol` (gRPC/HTTPS), no CORS annotations and no `canary-*` annotations.
- **Controller values.** `allow-snippet-annotations: "true"` and
  `annotations-risk-level: "Critical"` are set so that the security-header and Keycloak
  `/admin` snippets load. The admission webhook is **disabled**
  (`admissionWebhooks.enabled: false`).
- **Argo Rollouts.** Rollouts use `canaryService`/`stableService` with **no**
  `trafficRouting` (there is none under `openbank-infra`). Canary weight is
  replica-proportional, as `.github/canary-rollout-realisable-baseline.txt` records.
  Nothing couples Rollouts to nginx.
- **Coupled code.** Two artifacts depend on nginx. The Kyverno policy
  `require-gated-or-declared-tool-ingress-cel` (`cel-validating-policies.yaml`) keys on the `nginx.ingress.kubernetes.io/auth-url`
  annotation (ADR-0234). `gen-network-policies.py` derives `FROM ingress-nginx` edges
  from Ingress objects (gate `gen-network-policies-drift-gate`).
- **CNI.** The cluster runs the AWS VPC CNI, not Cilium, whatever ADR-0010's component
  list says.

## Decision

We will serve north-south HTTP through the Kubernetes **Gateway API**, implemented by
**Envoy Gateway**. Its multi-arch `v1.9.1` image publishes amd64 and arm64, confirmed with
`docker manifest inspect`. It sits behind **one** NLB with the same annotations and
public exposure as today. There is one shared `Gateway` (class `envoy`) in a platform
namespace, and each service owns its `HTTPRoute`.

The nginx annotations map as follows:

| nginx annotation | Replacement |
|---|---|
| `ssl-redirect`, `permanent-redirect` | HTTPRoute `RequestRedirect` filter |
| `upstream-vhost`, `proxy-set-headers` | HTTPRoute `URLRewrite` hostname / `RequestHeaderModifier` |
| `configuration-snippet` (security headers) | HTTPRoute `ResponseHeaderModifier`, so no snippet is needed |
| `server-snippet` (Keycloak `/admin` → 403) | a more specific HTTPRoute match to a direct-response / deny rule |
| `auth-url`, `auth-signin`, `auth-response-headers` | Envoy Gateway `SecurityPolicy.extAuth` (HTTP) against the same admin-ui session endpoint (ADR-0234 mechanism unchanged) |
| `limit-rps`, `limit-burst-multiplier`, `limit-connections` | `BackendTrafficPolicy.rateLimit` (local) and connection limits |
| `proxy-body-size`, `proxy-*-timeout`, `proxy-buffering` | `ClientTrafficPolicy` / `BackendTrafficPolicy` limits and timeouts; the 32k OIDC header buffer becomes `ClientTrafficPolicy` header limits |
| `use-regex` | HTTPRoute `RegularExpression` path match |
| `enable-modsecurity`, `enable-owasp-core-rules`, `modsecurity-snippet` | **no native equivalent.** The developer portal's WAF moves to its own edge (ADR-0093), or to a Coraza extension decided in that host's phase |

Snippets have no place in the target design. Each one becomes a typed field, which removes
the reason the controller was run at `Critical` risk.

### Migration plan

- **Phase 0 — install alongside.** Install the Envoy Gateway app, the Gateway, the
  `GatewayClass` and a second NLB with the same exposure. Nothing is routed yet. Upgrade
  external-dns to current (`v0.23.0` at the time of writing) and add
  `--source=gateway-httproute`. Before the bump, read the upgrade notes for the
  annotation-prefix change and check which of our annotations it touches. Teach
  `gen-network-policies.py` the Gateway namespace, and add an Envoy ServiceMonitor that
  keeps the edge-5xx signal from #2677.
- **Phase 1 — low-risk hosts.** Migrate pact-broker, langfuse, rum-gateway and
  developer-portal (once its WAF placement is decided). These carry no money path and no
  auth gate. Cut over through external-dns and keep the old Ingress until DNS is observed
  on the new NLB.
- **Phase 2 — gated tools.** Migrate `admin-ui/tools-gate.yaml` and the
  kube-prometheus-stack Grafana route to `SecurityPolicy.extAuth`. Rewrite the Kyverno
  policy to require a SecurityPolicy targeting every HTTPRoute in `observability`, and
  prove it with a must-reject case before relying on it.
- **Phase 3 — identity and admin.** Migrate Keycloak and admin-ui: header modifiers, the
  `/admin` deny rule, the OIDC header-size limit, and a login E2E run against the new host.
- **Phase 4 — money path and customer edge.** Migrate customer-edge, accounts, balances,
  payments, sanctions, copilot and `k8s/base`, with rate-limit parity tests at 429. If we
  want weighted canaries, the Argo Rollouts Gateway API plugin is the optional follow-up.
  That is a separate decision, because Rollouts use no traffic router today.
- **Phase 5 — remove.** Delete `gitops/apps/ingress-nginx.yaml` and its NLB. Remove the
  `ingress-nginx` edges from NetworkPolicy generation, and remove the nginx branch from the
  Kyverno policy.

### Interim hardening (until Phase 5)

- Keep `admissionWebhooks.enabled: false`. The unauthenticated admission-webhook RCE class
  (IngressNightmare, 2025) needs the webhook, and it is off.
- Snippets are the live risk. Any identity that can write an Ingress can inject nginx
  config and read the controller's secrets. Before any new snippet is added, restrict
  Ingress write in RBAC/ArgoCD projects to the GitOps path. Move the admin-ui security
  headers to `add-headers` ConfigMap entries now if that is feasible, so that the risk
  level can drop back to its default before the migration finishes.

### Phase 4 amendment (2026-10-04): `limit-connections` and glitchtip's body cap

Two nginx controls in Phase 4 have no exact Envoy Gateway counterpart. The table above
mapped them loosely; this records what each actually becomes and the residual risk.

- **`limit-connections: 10` (accounts, balances, payments and sanctions on
  api.open-bank.tech).** nginx capped concurrent connections *per client IP*. Envoy Gateway
  has no per-client concurrency limit. It is replaced by three coarser bounds:
  - the per-client-IP request **rate** limit (`BackendTrafficPolicy.rateLimit.local`,
    `sourceCIDR: Distinct`, 20/s, one bucket set per route as nginx kept one zone per
    Ingress);
  - a listener-wide `ClientTrafficPolicy.connection.connectionLimit` on `https-api`, sized
    from the measured peak: 107 active connections on ingress-nginx across *all* hosts and
    52 on a single Envoy replica, over Prometheus' ~3.8-day retention on 2026-10-04;
  - a per-backend `circuitBreaker` (`maxConnections`, `maxPendingRequests`,
    `maxParallelRequests`) on each route's BackendTrafficPolicy.

  **Residual risk.** One client can now hold more than 10 concurrent connections. Its
  request rate is still capped, the listener total bounds the host, and the circuit breaker
  bounds each service. A client that holds many idle or slow connections could exhaust the
  listener's budget for every other api.open-bank.tech client, where nginx confined it to
  ten. This was accepted because keeping an archived, unpatched controller on the
  money-path edge is the larger risk. Revisit if Envoy Gateway gains a per-client
  connection limit.
- **glitchtip `proxy-body-size: 64m`.** Envoy Gateway's only body cap (`requestBuffer`)
  holds the whole body in proxy memory, and 64 MiB per request against the proxies' 256Mi
  limit would let a few concurrent uploads OOM the shared edge. The route therefore sets
  **no edge body cap** and Envoy streams the body. The limits are GlitchTip's own: chunk
  uploads are refused above 32 MiB (`CHUNK_UPLOAD_BLOB_SIZE`) and other request bodies above
  15 MiB (Django `DATA_UPLOAD_MAX_MEMORY_SIZE`), both read from the running image on
  2026-10-04. Neither value is set in the manifests.

## Alternatives considered

- **AWS Load Balancer Controller (Gateway API).** It maps Gateways to AWS-managed ALBs and
  NLBs, which gives the best AWS integration. It was rejected because it breaks ADR-0027's
  cloud-agnostic edge. It also cannot provide ext-auth or local rate limiting, so the
  ADR-0234 gate and the customer-edge per-IP limits would have to move to Cognito, OIDC or
  WAF, which are AWS-specific. It also tends to one ALB per Gateway, which adds load-balancer
  hours.
- **NGINX Gateway Fabric.** It is a familiar data plane and multi-arch. It was rejected
  because the extension surface we need (external auth, rate limiting) is thinner than
  Envoy Gateway's typed policies. Its escape hatch is snippets again (`SnippetsFilter`),
  which is exactly the risk class we are leaving.
- **Cilium Gateway API.** It needs Cilium as CNI or kube-proxy replacement. We run the VPC
  CNI, so adopting it means a CNI migration of the whole cluster to replace one
  controller, and it was rejected.
- **Stay on archived ingress-nginx (or a fork).** This has zero migration cost, but it
  leaves an unpatched internet-facing component on the path of every customer request,
  running with Critical-risk snippets enabled. It was rejected.

## Consequences

**Positive**
- The edge is back on a maintained implementation of an upstream Kubernetes API, and
  snippets are gone.
- The auth gate, rate limits and headers become typed, reviewable objects. A Kyverno
  policy can check them without regex-matching annotations.

**Negative**
- **FinOps delta.** Steady state is one NLB, the same as today, so the delta is $0. During
  Phases 0–4 there is a second NLB at about 0.025 USD/h, roughly $18/month plus LCUs. Over
  a two-month migration that is about $36 in total, against the ~$300/month sandbox
  substrate (ADR-0062), or about +6% while both run. Envoy (control plane plus proxy) uses
  about the same requests as the current single controller.
- Every Ingress, the Kyverno policy, the NetworkPolicy generator and the edge-5xx alert
  change. Developer-portal WAF needs its own decision.
- **Gate.** No new gate is needed while both controllers run. In Phase 5 a checker is
  added under `.github/scripts/` that fails on any `kind: Ingress` with
  `ingressClassName: nginx` or any `nginx.ingress.kubernetes.io/` annotation. It runs in
  enforced mode in `Validate manifests`, so the removed controller cannot come back.

**Neutral**
- Argo Rollouts are unaffected, because they use no trafficRouting.
- cert-manager DNS-01 is unchanged. Certificates attach to Gateway listeners instead of
  Ingress `tls:`.

### Delivery check

```bash
git grep -l 'ingressClassName: *nginx' -- openbank-infra     # expect: no output
git grep -l 'nginx.ingress.kubernetes.io/' -- openbank-infra # expect: no output
test ! -e openbank-infra/gitops/apps/ingress-nginx.yaml && echo removed  # expect: removed
git grep -l 'kind: HTTPRoute' -- openbank-infra | wc -l       # expect: >= 14
```

## Compliance impact

- PCI DSS: not applicable — no card data flows change; the edge still terminates TLS in-cluster.
- DORA: ICT third-party / unsupported software — this retires an end-of-life internet-facing component.
- GDPR: not applicable — no change to what personal data is processed.
- PSD2: not applicable — TPP-facing routes move hosts unchanged.
- CNB: not applicable — no reporting impact.

## References

- ADR-0010, ADR-0027, ADR-0062, ADR-0093, ADR-0098, ADR-0234
- Tracking sweep: #11258
- `openbank-infra/gitops/apps/ingress-nginx.yaml`, `openbank-infra/gitops/components/kyverno/cel-validating-policies.yaml`
