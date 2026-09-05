<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — security-scanner

- **Date:** 2026-06-23
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Network-reach change for fleet-wide scanning.
- **Service ADR:** N/A (platform utility); network baseline ADR-0081; enforcement ADR-0060.

## 1. Scope & purpose

`openbank-security-scanner` is a platform-level probe that runs every 30 minutes and checks
all deployed services for: reachability, missing OWASP security headers, sensitive info
exposure on the management port, OpenAPI spec exposure, and unauthenticated actuator endpoints.
Results are held in memory and served over REST (`GET /api/v1/security/report`). DORA-grade
critical findings go to Kafka on `openbank.security.ict.incident` via a direct emitter.

`openbank.security.scan.event` and the transactional outbox behind it were removed in #4709:
they were fully provisioned — port, entity, repository, dispatcher, gauge, publisher, topic,
KafkaUser, mTLS and matching audit-side ACLs — and nothing ever constructed a message. Measured
before removal: 0 rows in `security_outbox` on the live database, end offset 0 on the topic, and
0 of 1979 `audit_entries` rows attributed to security-scanner. The service persists nothing: its
database now holds Flyway history only.

**This threat model covers the network-reachability grant in PR #1811 fix**: the scanner is
granted ingress from the `security-scanner` namespace into all 27 scan-target namespaces on
their API ports (e.g. 8100–8135) and management port (8085). Previously the scanner had no
network path to any target; the NPs dropped every probe silently.

## 2. Data flow (DFD)

```
[Quartz scheduler (30 min)] --> [SecurityScannerService]
                                    |
                                    +--> mgmt:8085  /q/health/ready   (each of 27 targets)
                                    +--> api:<port> (security-headers check, OpenAPI, actuators)
                                    |
                                    +--> [in-memory report] --> GET /api/v1/security/report
                                    +--> [Kafka direct emitter] --> openbank.security.ict.incident

  (No datastore in this flow. Scan results and ICT incidents live in ConcurrentHashMaps and are
   lost on pod restart; the CNPG Postgres holds flyway_schema_history and nothing else.)
```

- **No OIDC credentials** in the scanner pod (no client_id/secret, OIDC disabled).
- **No inbound calls** from other services to the scanner API — scanner is egress-only by design.
- **Management port** (8085) is auth-free on every target; API ports require OIDC bearer.

## 3. New attack surface introduced

| Target | Port granted | Why needed |
|---|---|---|
| All 27 services | API port (8100–8135) | Security-headers + OpenAPI + actuator probe |
| All 27 services | Management port (8085) | Reachability + health-sensitive-info check |

This is a fleet-wide ingress change: the `security-scanner` namespace is now an authorised
caller for every production service's API and management ports within the cluster.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Scanner pod impersonates a human/operator | No OIDC credentials in the pod → cannot obtain a bearer token → money-path mutations require auth → rejected 401. Scanner is namespace-scoped (NP ingress, not egress-from-any). |
| **T**ampering | Scanner issues mutating requests to money-path services | Scanner only issues `GET` requests to read-only probes (`/q/health`, headers check, `/q/openapi`, well-known actuator paths). No POST/PUT/DELETE. |
| **R**epudiation | Scanner leaves no audit trail of its probes | The scanner's probes are unauthenticated (401 → logged at INFO on callee) or return 200 on public routes. Audit trail captures authenticated calls only — consistent with management-port design. |
| **I**nfo disclosure | Scanner reads sensitive data via API port | API port endpoints require OIDC bearer; scanner has none → receives 401. Publicly visible data (health status, response headers, OpenAPI spec) is intentionally cluster-internal already. |
| **I**nfo disclosure | Management port (8085) leaks sensitive runtime config | Management port is auth-free by design (health/metrics); already admitted from `observability` namespace. Scanner gets the same signal. No secrets, keys, or financial data served on 8085. |
| **D**oS | Scanner overwhelms a money-path service | 30-minute interval, ~27 sequential GET requests per cycle, each with `connectTimeout=5s`. Volume is negligible vs normal traffic. |
| **E**oP | Compromised scanner pod used to pivot to money-path services | **Primary residual risk** (see §5). Scanner has network reach but no credentials. Exploiting application-layer vulnerabilities still requires bypassing OIDC on all protected endpoints. |

## 5. Residual risks

1. **Compromised scanner image (supply-chain)**: An attacker controlling the scanner image gains
   a network-adjacent position to all money-path services. They cannot authenticate (no
   credentials in the pod env), but they can probe for unauthenticated routes, attempt SSRF
   from within the pod, or run network reconnaissance. **Accepted for sandbox** given the scanner's
   restricted security context (non-root, readOnlyRootFilesystem, all caps dropped, no host
   network). For production, the scanner image digest must be signed (cosign KMS, ADR-0088)
   and the image-scan gate (Trivy, ADR-0030 D3) must block HIGH/CRITICAL CVEs in the scanner
   image itself.

2. **Unauthenticated routes not tested**: The scanner checks a hardcoded set of actuator paths.
   A new unauthenticated route introduced in a money-path service that the scanner does not
   probe would not be detected. Mitigation: **partial, and narrower than this entry claimed.**
   There is no fleet-wide `SecurityContractTest` — no class by that exact name exists, and the
   invariant is enforced per service by nine hand-written variants
   (`AccountSecurityContractTest`, `BalanceSecurityContractTest`, `ClearingSecurityContractTest`,
   `DocumentSecurityContractTest`, `LedgerSecurityContractTest`, `YearCloseSecurityContractTest`,
   `StandingOrderSecurityContractTest`, `TppRegistrySecurityContractTest`,
   `TransactionSecurityContractTest`) covering **8 of the 61 modules that expose a JAX-RS
   resource**. Each of those does enforce the stated invariant for its own service, by reflection
   over the resource class, so for those 8 the mitigation is real and strong. For the other 53 —
   including security-scanner itself, which has no such test — nothing enforces it, so the
   scanner's hardcoded probe set is not a belt-and-suspenders check but the only automated check,
   and it is exactly the one this residual says is incomplete. Making the invariant fleet-wide (a
   shared reflective test in `openbank-libs`, or a CI gate over `@Path` classes) is the real
   mitigation and is not in place.

3. **Management port drift**: If a service begins serving sensitive data on port 8085 (contrary
   to Quarkus management-port design), the scanner has network access to it. Mitigation: existing
   management-port tests and the "no-sensitive-management-port" scan check (step 3 of
   `SecurityScannerService.scanService`) would flag it.

## 6. Change log

- **2026-09-03** — Doc correction, no behavior change: §5.2 credited its mitigation to "the
  security-contract test (`SecurityContractTest`)" enforcing that "**every** JAX-RS endpoint is
  annotated", and called it "the primary defence". `SecurityContractTest` does not exist as a class —
  `git grep -nE 'class SecurityContractTest\b' -- '*.kt'` returns nothing — and the name is not a
  rename of one thing but a family label for nine per-service variants. Measured against the tree:
  61 modules declare a JAX-RS resource, 8 of them carry such a test. security-scanner is not one of
  the 8.

  The consequence is specific rather than cosmetic, which is why the residual is rewritten rather
  than just renamed. §5.2's risk is "a new unauthenticated route in a money-path service the
  scanner does not probe". The mitigation as written retired that risk by asserting a fleet-wide
  invariant; the invariant covers 8 modules, so for the remaining 53 the scanner's hardcoded probe
  list is the only automated check — and the residual exists precisely because that list is
  incomplete. The mitigation was, for most of the fleet, the thing it was mitigating.

  **What still holds:** for the 8 covered modules the control is real and strong — each test walks
  its resource class by reflection and fails the build on a `@PermitAll` or an unannotated
  endpoint, which is how the balance and clearing regression guards referenced in their own threat
  models work. Nothing here changes the scanner, any service's annotations, or a role. Closing the
  gap properly means a shared reflective test in `openbank-libs` or a CI gate over `@Path` classes,
  which is a code change and deliberately not made in a docs commit.

- **2026-06-23** — Initial threat model for the fleet-wide network-reachability grant (PR #1811 fix).
  Scanner previously had no network path to any target — this grant enables the scanner to
  actually scan. Management port (8085) and API port granted to all 27 services in the scan list.
