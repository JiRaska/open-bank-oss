# Threat Model — Identity-aware edge gate for internal tool UIs

**Component:** the edge path that serves Grafana, Alertmanager and Pyrra under
`admin.open-bank.tech/tools/*` behind the admin-UI session check
**ADRs:** ADR-0234 (the gate), ADR-0324 (moving the edge from ingress-nginx to Envoy Gateway — this
document is written for its Phase 2), ADR-0030 (threat-model discipline)
**Status:** Phase 2 staged — the Envoy Gateway path is built and held out of DNS; ingress-nginx
serves all traffic until the Phase 3 cutover of `admin.open-bank.tech`
**Review cadence:** on any change to the gate's allow-list, to either edge's gate wiring, or to the
Kyverno tool-gate policies

> Not a money-path service, so `check-threat-models.py` does not require this document. It exists
> because ADR-0324 moves the enforcement point of an authentication boundary from one proxy to
> another, and for the length of the migration BOTH proxies can reach the tools.

---

## 1. Scope and trust boundaries

**Assets.** Grafana (dashboards; Explore over Prometheus, Loki and Tempo for Editor and above),
Alertmanager (its UI is its API: anyone who can load it can silence or expire any alert), Pyrra
(read-only SLO console). None of the three has a pre-auth surface that is safe to expose: the gate
exists so that their pre-auth surface stays as unreachable as it was with no route (ADR-0056).

**The boundary.** One decision, made by the admin UI (its lib/auth/toolGate module, outside the backend corpus the
claims gate reads): no session or a failed Keycloak refresh is
unauthenticated; an unknown tool, a role on the tool's deny-list (the public demo account on
Alertmanager) or a missing `system:view` permission is forbidden; otherwise allow. It is answered in
two spellings, one per edge:

| | ingress-nginx (`components/admin-ui/tools-gate.yaml`) | Envoy Gateway (`components/observability/httproute-tools-gate.yaml`) |
|---|---|---|
| Who asks | `auth_request` sub-request per request | ext_authz HTTP check per request (`SecurityPolicy.extAuth`) |
| Gate URL | `/api/gate?tool=<t>` | `/api/gate/<t>` + the original path and query, appended by Envoy |
| Tool selected by | the Ingress annotation | the SecurityPolicy `path` — never by the caller in either case |
| Credentials forwarded | all request headers | `cookie` only (`headersToExtAuth`), plus Envoy's fixed Host/Method/Path/Content-Length/Authorization |
| Allow | 204 | 200 (Envoy treats any other status as a denial) |
| No session | 401, `auth-signin` redirects | 302 to `/auth/login?callbackUrl=<the tool URI>`, issued by the gate and relayed by Envoy |
| Gate unreachable | 500 (fail closed) | 403 (`failOpen: false`, Envoy's default `status_on_error`) |
| Gate output to the tool | none (no `auth-response-headers`) | none (no headers copied from the gate response to the backend) |

## 2. STRIDE

| ID | Threat | Mitigation | Residual |
|---|---|---|---|
| **S1** | A request reaches a tool without a session because one edge is configured and the other is not (the migration window). | Every HTTPRoute in `observability` must be targeted by a SecurityPolicy carrying extAuth, or declare `openbank.io/ungated-machine-callers` — Kyverno `require-gated-or-declared-tool-httproute-cel`, enforcing, next to the unchanged Ingress policy `require-gated-or-declared-tool-ingress-cel`. The tool routes are created after their policies (sync-wave −1 / 0). | A route in another namespace that names a tool Service is not matched; the Gateway listener for this host admits only `admin-ui` and `observability`, and a cross-namespace backend needs a ReferenceGrant in `observability`, of which there are none for the tools. |
| **S2** | The gate is removed or weakened without touching the route (delete the SecurityPolicy, remove `extAuth`, or set `failOpen: true`). | Kyverno `protect-tool-httproute-gate-cel` denies a SecurityPolicy UPDATE/DELETE that would leave a live, undeclared route with no fail-closed extAuth policy. The companion route policy also refuses a route covered only by fail-open extAuth. The original Kyverno 1.19.1 webhook test covered delete and extAuth-removal; the fail-open route case is pinned in the CLI fixture. | Recheck the SecurityPolicy UPDATE webhook verdict for `failOpen: true` before DNS cutover. |
| **S3** | The caller picks a wider tool: a request for `/tools/alertmanager` checked as Grafana. | One HTTPRoute and one SecurityPolicy per tool; the tool name is the fixed first segment of the policy's `path`. The deep-link callback is accepted only under that same tool's prefix. | Path normalisation is Envoy Gateway's default; a route matches by `PathPrefix` on the normalised path. |
| **T1** | Forged identity headers reach a tool. | No gate response header is copied to the backend on either edge (no `auth-response-headers` on nginx, no backend header list on the SecurityPolicy): the tools authenticate the operator through their own SSO (Grafana generic OAuth) and never read gate output. | None new. |
| **R1** | A gate decision cannot be attributed. | Envoy access log + `envoy_http_downstream_rq_xx` per route; the admin-UI logs its requests. | The edge-5xx alerts (#2677) still describe the nginx path for this host until the Phase 3 cutover retargets them. |
| **I1** | Open redirect via the login callback. | The gate builds a relative `Location` (no Host header trusted) and accepts a callback only if it is a same-origin path under `/tools/<t>`; anything else falls back to `/auth/login`. Auth.js validates `callbackUrl` against its own base URL as a second layer. | None known. |
| **D1** | The gate is a dependency of every tool request. | Fail closed on both edges; `grafana-local.sh` remains the break-glass path when the admin UI is down (ADR-0234). Request body cap 1 MiB and 60 s upstream timeout, as nginx's defaults. | An admin-UI outage takes the tools with it, by design. |
| **E1** | A pod reaches a tool directly, bypassing the edge. | NetworkPolicies admit only the same namespace, `admin-ui`, `ingress-nginx` and (Phase 2) `envoy-gateway-system` to the tool pods; the admin-UI's own allow-list admits `envoy-gateway-system` on :3000 only because a SecurityPolicy names it (derived by `gen-network-policies.py`). | Either edge namespace can reach the tools in-cluster without the gate; that is the same trust nginx had, and the nginx peer goes in Phase 5. |

## 3. What changes at the Phase 3 cutover

The tools share `admin.open-bank.tech` with the console, so DNS moves for all of them at once, with
the admin-UI route. Until then the Envoy path answers only on the Gateway's own NLB address. Before
the cutover, verify against that address with `curl --resolve`: no cookie must give a 302 to
`/auth/login?callbackUrl=...`, never a tool response.
