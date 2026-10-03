<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-audit-service

Status: implementation review required. Scope: the authenticated REST read/verify surface and its
authorization wiring. This model does not certify production deployment or regulatory compliance.
`openbank-audit-service` is not listed in `rules.yaml: money_path_services`; this document is kept
voluntarily because the service holds the fleet's evidentiary record.

## Assets and trust boundaries

The protected assets are the persisted audit entries, their hash-chain links and the integrity
verdicts the service reports. Callers reach the REST surface through OIDC authentication and the
`@Authorize` interceptor; each authorization decision crosses the boundary to the per-pod OPA
sidecar.

## Change log

### 2026-10-03 — policy decision point wiring

The service now opts in to the shared libs-runtime `OpaPolicyDecisionPointProducer`
(`openbank.authz.opa-pdp-producer.enabled: true`), which registers `OpaSidecarPolicyDecisionPoint`
as the CDI policy decision point. `opa.url`, `opa.path` and `opa.timeout-ms` select the sidecar
and bound each request. Before this, no `PolicyDecisionPoint` bean existed in the service: a
running OPA sidecar alone did not authorize anything — enforced `@Authorize` requests took the
interceptor's `pdp_unconfigured` branch and advisory mode never consulted the policy.

- **Elevation of privilege:** with the producer present, an OPA deny is enforced even for a caller
  whose role would otherwise look eligible. `AuditAuthzWiringIT` drives real HTTP through CDI and
  the interceptor against a local policy fixture and asserts both an allow (200) and a deny (403).
- **Denial of service:** a missing or unreachable sidecar remains fail-closed when enforcement is
  enabled; the timeout bounds how long a request waits on it.

Residual: the deployed enforcement setting and the role/action policy are unchanged by this
wiring and are not attested by the test, which uses a fixture rather than the generated bundle.
