---
date: 2026-10-29
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [security-ops, networking, observability, gitops]
followup: "#14163 — decoy paths, the Loki alert and the parity gate ship here; the decoy hostname, honeytokens (canary key, honey user, honey row, honey Secret), the SOC panel and the fate of the inert HoneytokenFilter are open"
summary: "Deception moves to the shared edge: Envoy Gateway answers decoy paths itself and a Loki rule alerts on hits from the access log, so a honey endpoint needs no service config; a gate keeps route and rule paired."
---

# ADR-0357 — Edge deception layer: decoy paths on the shared gateway, alerted from the access log

## Context

ADR-0279 (WS2, security observability) decided that honeytokens and honey endpoints run in the
sandbox wired to alerts, and the primitives shipped: `HoneytokenFilter` in `openbank-libs-runtime`
answers a configured path with 404, counts the hit and logs one line, and
`loki-rules-security-signals.yaml` turns that line into `HoneyEndpointHit`. This ADR is needed
because that control is write-only in the estate as built. Measured 2026-10-29 against `main` and
the sandbox: `openbank.security.honey.paths` is set in no service's `application.yaml` and in no
gitops manifest, so the filter is inert in every pod; the Prometheus series
`openbank_security_honeytoken_hits_total` does not exist; `HoneyEndpointHit` fired 0 times in 7
days, and could not have fired. A honey endpoint that each service has to opt into is one that no
service opts into — the same shape as the `@ConfigProperty` and `migrate-at-start` lessons in the
root guide: prose asked for it, nothing enforced it.

ADR-0324 made Envoy Gateway the shared edge: one Gateway, one listener per hostname, routes
admitted per namespace, and per-route `HTTPRouteFilter.directResponse` already in use
(`iam/keycloak-admin-block`). Its access log is JSON to stdout, shipped by Alloy into Loki with
`route_name`, `path`, `response_code` and the client address on every line. ADR-0093 puts a WAF
(ModSecurity) in front of exactly one host, the developer portal; ADR-0234 gates the internal tool
UIs on `admin.open-bank.tech` behind the admin-UI session. The 2026-09-29 decision keeps the
sandbox edge open to the internet with no source allow-list.

A one-day sample of the edge access log, read for this ADR, shows what that openness attracts:
commodity reconnaissance for `.env`, `.git/HEAD`, WordPress and PHP artefacts and
`actuator/heapdump`, from a rotating set of sources, a few dozen requests a day. None of it is a
signal today — it lands as 404 and 403 lines that nothing aggregates, so "are we being probed, and
by whom" has no answer short of writing LogQL by hand. There is no targeted activity in the
sample; the point of a deception layer is to know the day that changes.

Two constraints shape the answer. The repository is public, so any decoy definition is readable
by an attacker. And the platform is a sandbox under FinOps scrutiny: a second load balancer, an
EC2 honeypot or a new always-on pod is a recurring cost for telemetry that the existing edge and
log pipeline can produce for nothing.

## Decision

1. **Decoys live at the edge, not in services.** A dedicated HTTPRoute pair in the
   `observability` namespace attaches to the shared Gateway and answers decoy paths with
   `HTTPRouteFilter.directResponse`. No request reaches a backend, no service needs
   configuration, and the decoy exists for a hostname from the moment the route is admitted.
   Exact-path matches take precedence over the application routes' prefix matches by Gateway API
   precedence, so a decoy can sit on a hostname another namespace serves.

2. **Two tiers, distinguished by route, never by value.** `edge-decoys-commodity` carries the
   paths internet scanners try everywhere (`/.env`, `/.git/HEAD`, `/wp-login.php`,
   `/phpinfo.php`, `/actuator/env`, `/actuator/heapdump`) and answers 200 with a harmless body, so
   the scanner records a hit and keeps probing. `edge-decoys-targeted` carries OpenBank-shaped paths
   no legitimate client can know (`/api/v1/internal/ledger/export`, `/api/v1/admin/backup`,
   `/api/v0/accounts`) and answers 401 JSON, revealing nothing. The tier is the route name, which
   Envoy writes into every access-log line as `route_name`, so the rules below need no body parsing
   and no path list of their own.

3. **Detection reads the access log, with one rule per tier.** `loki-rules-edge-deception.yaml`
   adds `EdgeDecoyTargetedHit` (any hit on the targeted route, no `for:`, severity `warning` to
   Slack under the estate's new-rule convention, with the promotion criterion written in the rule)
   and a recording rule `openbank:edge_decoy_commodity_hits:rate5m` by path for the SOC
   dashboard. Commodity hits are telemetry and never alert on their own: the one-day sample shows
   they arrive daily, and an alert that fires daily is muted within a week.

4. **Route and rule are kept paired by a gate.** `check-edge-decoy-rule-parity.py` fails when a
   decoy HTTPRoute exists whose `route_name` no Loki rule selects, or a rule selects a route that
   no manifest declares. That is the mechanism the ADR-0279 slice lacked: a decoy with no reader,
   or a reader with no decoy, cannot merge.

5. **Decoy definitions stay in gitops, in the open.** A readable decoy is still a tripwire: an
   attacker who has read the list must avoid every listed path without knowing which real paths
   sit next to them, and the commodity tier is aimed at automation that reads nothing. If a
   targeted path set ever needs to be secret, it moves to an ExternalSecret-sourced ConfigMap; that
   is a follow-up, not a reason to start with a secret.

6. **Nothing new runs.** No pod, no Service, no NetworkPolicy, no load balancer, no EC2. The
   recurring cost of this decision is the access-log lines the decoys add to Loki, which the
   sample bounds at a few dozen a day.

## Alternatives considered

- **Configure `openbank.security.honey.paths` on each service (the ADR-0279 shape as built).**
  Per-service, post-TLS and inside the JAX-RS chain, so it sees only paths routed to a service
  that opted in, and it has never been opted into. Keeps every hostname without a Quarkus backend
  (admin, keycloak, tools) uncovered and every commodity probe invisible, since those paths are
  never routed to a service at all. Rejected as the primary layer; kept as a possible second layer
  for paths behind authentication, with its retirement or first configuration tracked in #14163.
- **A separate honeypot host (T-Pot or Cowrie on an EC2 instance with its own public address).**
  Rich protocol emulation, but it reports what GuardDuty and the edge log already show (commodity
  noise), costs a recurring instance and its patching, and adds an exposed system outside GitOps
  and outside the admission policies. Rejected on FinOps and blast-radius grounds; a decoy
  hostname on the existing Gateway (follow-up) gives the "someone enumerated our zone" signal at
  no cost.
- **A WAF in blocking mode on every host (AWS WAF, or Coraza in Envoy Gateway).** AWS WAF does
  not attach to an NLB, and an ALB in front would discard the client-address model the per-client
  rate limits depend on (ADR-0324). Coraza as an Envoy extension is a telemetry source worth
  adding in detection-only mode, but it answers a different question (what payloads hit real
  routes), has a non-zero false-positive rate on a JSON API, and adds a component on the
  money-path edge. Deferred, not rejected; tracked in #14163.
- **Alert on the commodity tier too.** Zero marginal cost and maximal noise: the sample shows
  daily hits from rotating sources, so the alert would be standing within a week and would hide the
  targeted one next to it. Rejected; the commodity tier is a recording rule and a panel.

## Consequences

**Positive**
- A honey endpoint exists for a hostname by declaring it once, on the edge, with no service
  change and no release.
- "Are we being probed" becomes a panel and a rule instead of a hand-written LogQL query.
- The targeted tier is the rare alert with a false-positive rate of zero by construction: no
  link, no documentation and no client ever names those paths.
- Zero recurring infrastructure cost; the only ongoing cost is a few log lines a day.

**Negative**
- Serving 200 on `/.env` makes the edge more attractive to commodity scanners, which is the price
  of seeing them; the rate limits in ADR-0324 bound the cost.
- The decoy list is public. Mitigated by decision 5 and by the gate; not eliminated.
- Until the decoy-hostname follow-up lands, only hostnames already served by the Gateway carry
  decoys; hosts still on ingress-nginx are covered when their ADR-0324 cutover moves them.

**Neutral**
- `EdgeDecoyTargetedHit` starts at `warning`. Promotion criterion, in the rule file: flip to
  `critical` once a known-positive (a manual request against a targeted decoy in the sandbox) has
  been shown to reach Slack end to end, which is the drill runbook-0018 already schedules.
- Gate: `edge-decoy-rule-parity` (group `gitops`, mode `enforced`) pairs every decoy route with a
  rule and every rule with a route.

### Delivery check

```bash
# every decoy route has a reader and every reader has a route (exit 0, no findings)
python3 .github/scripts/check-edge-decoy-rule-parity.py
# the routes are admitted on the live Gateway (both Accepted=True)
kubectl -n observability get httproute edge-decoys-commodity edge-decoys-targeted
# a known-positive: the targeted decoy answers 401 from Envoy, not from a backend
curl -s -o /dev/null -w '%{http_code}\n' https://admin.open-bank.tech/api/v1/admin/backup   # 401
# and lands in Loki within a minute as a hit on the targeted route
# {namespace="envoy-gateway-system", container="envoy"} | json | route_name=~"httproute/observability/edge-decoys-targeted/.*"
```

If the first command reports a finding, or the third answers anything but 401, the layer is not
delivered regardless of the status field.

## Compliance impact

- PCI DSS: not applicable — the sandbox holds no cardholder data; the decoys carry no data.
- DORA: strengthens ICT-related incident detection evidence in the sense ADR-0279 already claims for its security observability work stream; no new obligation is asserted.
- GDPR: not applicable — the decoys log the client address the access log already records; no new personal data is collected.
- PSD2: not applicable.
- CNB: not applicable.

## References

- #14163 — tracking issue, follow-ups and measurements
- ADR-0279 — security roadmap, WS2 honey endpoints (the inert primitive this ADR replaces at the edge)
- ADR-0324 — Envoy Gateway as the shared edge, `directResponse` precedent in `iam/keycloak-admin-block`
- ADR-0093, ADR-0234 — the WAF and the tool gate this layer sits beside
- `openbank-infra/gitops/components/observability/httproute-edge-decoys.yaml`
- `openbank-infra/gitops/components/observability/loki-rules-edge-deception.yaml`
- `.github/scripts/check-edge-decoy-rule-parity.py`
