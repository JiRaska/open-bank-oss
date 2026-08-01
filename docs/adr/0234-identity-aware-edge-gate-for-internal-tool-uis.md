---
date: 2026-08-01
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authn, networking, observability, admin-ui]
summary: "Internal tool UIs (Grafana first) are served as sub-paths of admin.open-bank.tech behind an nginx auth_request gate that validates the admin-UI session, with the tool's own SSO kept as a second, independent layer."
---

# ADR-0234 — Identity-aware edge gate for internal tool UIs

## Context

ADR-0056 made the admin-UI BFF the sole browser→cluster path, and the platform has
held that line by giving internal tools **no** Ingress at all: Grafana, GlitchTip's
web UI, ArgoCD and the Pact Broker are reached with `kubectl port-forward`
(`openbank-infra/scripts/grafana-local.sh`). That is genuinely safe — an attacker
cannot reach a pre-auth surface that is not routed — and it is also the reason
observability is used less than it should be. A port-forward needs cluster
credentials, a terminal and a free local port, so the operator who is already
signed in to the console cannot follow a link from an alert to the dashboard that
explains it.

The obvious fixes are all worse than the problem:

- Publishing `grafana.open-bank.tech` puts Grafana's login page, its `/api/`
  surface and every CVE in its release train on the internet. Grafana is the single
  richest post-auth pivot in the cluster (it holds datasource credentials and can
  issue arbitrary queries against Prometheus, Loki and Tempo).
- Trusting a proxy-injected identity header (`auth.proxy` / `X-WEBAUTH-USER`) turns
  *any* in-cluster pod that can reach the Grafana Service into a Grafana admin. There
  is no source-IP allow-list for that mode; the only control is NetworkPolicy, i.e. a
  single control with nothing behind it.
- A dedicated identity-aware proxy (oauth2-proxy, Pomerium) is a new public component
  with its own pre-auth surface, its own CVE stream and a second definition of "who is
  an operator" that will drift from the admin-UI's.

There is also a hard technical constraint that decides the URL shape rather than
merely influencing it. The gate has to see the caller's session, and the admin-UI
session cookie is `__Secure-authjs.session-token`, `SameSite=Lax` and **host-only**
(`openbank-admin-ui/src/lib/auth/authOptions.ts`). A browser will not send it to
`grafana.open-bank.tech`, so a gate on any other hostname would see no session and
deny every request. Making the cookie domain-wide (`.open-bank.tech`) would fix that
by handing the operator session to every subdomain — including the public GlitchTip
ingest host and Keycloak. That is a strictly worse trade than the problem being
solved, so the tools must live under `admin.open-bank.tech` itself.

## Decision

We will serve internal tool UIs as **sub-paths of `admin.open-bank.tech`, behind an
nginx `auth_request` gate that validates the admin-UI session, while the tool keeps
its own SSO as a second and independent authentication layer.**

Four parts:

1. **Edge gate.** A dedicated Ingress object (`admin-ui-tools`, namespace `admin-ui`)
   carries the `/tools/*` paths and the `nginx.ingress.kubernetes.io/auth-url` +
   `auth-signin` annotations. nginx issues a sub-request to the gate before
   `proxy_pass`, so an unauthenticated request never reaches the tool — the tool's
   pre-auth surface stays exactly as unreachable as it is today. The gate must be its
   own Ingress object: ingress-nginx applies annotations to every location of the
   object that carries them, so putting `auth-url` on the existing `admin-ui` Ingress
   would gate `/auth/login` against itself.

2. **Gate endpoint.** `GET /api/gate` in the admin-UI (`openbank-admin-ui/src/app/api/gate/route.ts`)
   validates the Auth.js session, resolves the requested tool from `?tool=`, checks the
   tool's required roles against the session's realm roles, and returns `204` or `401`.
   It returns no body on success and never proxies anything. It is the one route
   excluded from `src/middleware.ts`, because the middleware answers an unauthenticated
   request with a `302` to the login page and nginx maps any non-2xx/401/403 auth
   sub-response to a `500`.

3. **The tool keeps its own SSO.** Grafana's `auth.generic_oauth` against the `openbank`
   realm is already configured and stays on; its `root_url` moves to the sub-path and
   `serve_from_sub_path` is enabled. We do **not** enable `auth.proxy`, and the gate
   forwards no identity headers. So the two layers fail independently: the gate decides
   whether the request reaches Grafana at all, and Grafana independently decides who the
   user is from a Keycloak token. Because both layers use the same realm and the operator
   already has an SSO session, the second layer costs a silent redirect, not a login.

4. **Network and admission controls behind the edge.** A NetworkPolicy restricts the
   Grafana pod's ingress to the ingress-nginx controller, the `admin-ui` namespace and
   its own namespace (Prometheus scrape), so the ungated `.svc` path is closed for
   everything else. A Kyverno `ClusterPolicy` rejects any Ingress in `observability`
   that neither carries `auth-url` nor is on the machine-caller allow-list — a Helm
   upgrade that flips `ingress.enabled` cannot quietly re-expose a UI.

**Split routing is mandatory where a tool has machine callers.** GlitchTip's SDK ingest
and `sentry-cli` dSYM upload authenticate by DSN or org token and cannot follow a login
redirect, so those paths stay on the existing ungated `glitchtip.open-bank.tech` Ingress
(ADR-0075, PR #3045) and only the UI moves behind the gate. Gating a whole host that also
takes machine traffic breaks ingest silently — the SDK swallows the redirect.

**Scope of this ADR's delivery: Grafana.** GlitchTip's UI is deliberately *not* moved yet
and stays port-forward-only, so this ADR ships as `partial`. GlitchTip supports a sub-path
(`BASE_PATH`), but two things are unresolved and neither can be settled from the repo:
its Django `CSRF_TRUSTED_ORIGINS` must accept `admin.open-bank.tech` while `GLITCHTIP_DOMAIN`
stays `glitchtip.open-bank.tech` (the domain the displayed DSN is built from — changing it
points every device's SDK at the gated host), and its OIDC provider is configured through
the Django admin UI at `/admin/socialaccount/socialapp/`, i.e. runtime database state that
GitOps does not declare. Shipping that untested would trade a working ingest path for a
dashboard link. Phase 2 tracks it.

## Alternatives considered

- **Reverse-proxy the tool through a Next.js route handler** (the shape the existing
  `/api/prometheus/[...path]` and `/api/tempo/[...path]` routes use). Pro: no new
  Ingress, the BFF stays literally the sole path, and the session check is plain
  TypeScript. Con: those routes proxy a JSON API, not an application. Proxying a full
  SPA means rewriting asset URLs, relaying WebSockets for live panels, and re-signing
  cookies through a route handler — every one of which is a place to introduce a bug in
  the security boundary itself. Rejected: more code inside the trust boundary than
  `auth_request` puts in front of it.
- **A dedicated identity-aware proxy (oauth2-proxy / Pomerium / Teleport).** Pro: the
  industry-standard answer, mature, handles many upstreams. Con: a new public component
  with its own pre-auth surface and CVE stream, plus a second definition of "operator"
  that will drift from the admin-UI's role guards. Rejected: for two tools, the gate is
  ~40 lines of TypeScript reusing the session the console already issues.
- **Grafana `auth.proxy` with a gate-injected identity header.** Pro: single sign-in,
  no second redirect. Con: any pod able to reach the Grafana Service becomes a Grafana
  admin by setting a header, with NetworkPolicy as the only control. Rejected: it
  collapses two independent layers into one and makes a network control load-bearing
  for authentication.
- **Per-tool hostnames behind the same gate** (`grafana.open-bank.tech`). Pro: no
  sub-path support needed from the upstream, so it works for tools that have none.
  Con: the host-only session cookie is not sent cross-host, so the gate would have to
  be fed by a `.open-bank.tech`-scoped cookie — leaking the operator session to every
  subdomain including public ones. Rejected on that alone.
- **Tailscale / WireGuard, no public route at all.** Pro: strictly the smallest attack
  surface; nothing is reachable without being on the tailnet. Con: a new identity
  system to operate, and it does not answer the actual request (a link from the console
  to the dashboard). Not rejected on merit — it is a different product decision, and it
  composes with this one rather than replacing it.

## Consequences

**Positive**
- The tool's pre-auth surface stays off the internet: nginx denies before `proxy_pass`,
  so the change adds a *routed* path but not an *unauthenticated* one.
- Two independent authentication layers, neither of which trusts a header from the other.
- One hostname, one TLS certificate, one NLB — no new public DNS name, no new component.
- Access decisions become auditable and revocable in one place: removing an operator's
  realm role closes both the console and every tool behind the gate.
- Cost is zero: the same ingress-nginx controller and the existing certificate.

**Negative**
- The admin-UI becomes an availability dependency of Grafana: `auth_request` fails
  closed, so if the gate pod is down the dashboards are unreachable. This is the correct
  failure direction and is the reason the port-forward script is kept as break-glass.
- Grafana has exactly one `root_url`. Moving it to the sub-path means `grafana-local.sh`
  no longer completes an OIDC login (it redirects to the public host); the port-forward
  remains usable for a local Grafana-admin login only.
- `/api/gate` is the first route excluded from the middleware matcher, which has been
  "everything except Auth.js and static assets" since ADR-0080 P0. The route performs
  the same session check itself and returns no data, but the exclusion list is now a
  thing that has to be read carefully rather than a single rule.

**Neutral**
- Adding a tool is one Ingress path, one `ExternalName` Service, one entry in the gate's
  tool table and one NetworkPolicy — deliberately mechanical.
- The gate ties the tool to the admin-UI's session lifetime (1 h idle, ADR-0080 P2).

## Compliance impact

- PCI DSS: not applicable — no cardholder data traverses these tools.
- DORA:    supports the ICT access-control and logging expectations the platform already
           claims by making operator access to observability tooling go through the same
           authenticated, role-checked, auditable path as the console. No new claim.
- GDPR:    not applicable — the gate stores nothing; it reads the existing session cookie
           and returns a decision. Personal data in Grafana is unchanged by this ADR.
- PSD2:    not applicable — no payment-service interface is exposed or altered.
- CNB:     not applicable — no reporting interface is exposed or altered.

## References

- ADR-0056 — Admin-UI BFF as the sole browser→cluster path
- ADR-0075 — GlitchTip as the crash/error sink (ingest paths are the machine-caller half)
- ADR-0080 — Admin-UI security hardening (P0 middleware matcher, P1 CSP, P2 session TTL)
- ADR-0081 — NetworkPolicy baseline derived from declared GitOps edges
- ingress-nginx external authentication: `nginx.ingress.kubernetes.io/auth-url`, `auth-signin`
- Grafana `root_url` / `serve_from_sub_path`: https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/
- GlitchTip `BASE_PATH` and social-app configuration: https://glitchtip.com/documentation/install
