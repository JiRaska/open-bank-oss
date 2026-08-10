<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-anacredit-service

- **Date:** 2026-06-19
- **Status:** Lightweight STRIDE (ADR-0030 D2). Non-money-path regulatory reporting.
- **Purpose:** EU AnaCredit regulatory submission — granular credit data reported to the ECB/CNB.

## 1. Scope & purpose

AnaCredit is a European credit registry. This service reads loan/credit data from `lending-service`
and the ledger, transforms it into the AnaCredit data model, and produces report submissions.
It does **not** hold primary customer data or move funds. It is an **outbound regulatory reporting**
service — read-only access to internal data, write-only to the regulator's endpoint.

## 2. Data flow (DFD)

```
[lending-service]  --read--> [openbank-anacredit-service] --submit--> [ECB/CNB endpoint (HTTPS)]
[ledger-service]   --read--> [openbank-anacredit-service]
```

- **External entities:** ECB/CNB reporting endpoint (outbound HTTPS).
- **Trust boundaries:** internal cluster → service (mTLS + OIDC + OPA); service → ECB (TLS, credential-based).
- **Assets:** aggregated credit portfolio data (amounts, maturities, party identifiers), submission credentials.

## 3. Authn/Authz

- All internal REST endpoints: `@RolesAllowed("ROLE_ADMIN", "ROLE_COMPLIANCE")` (verified by `AnaCreditSecurityTest`).
- Outbound ECB/CNB calls use a service-level credential stored in **OpenBao** (never in-image).
- OPA sidecar (ADR-0034) enforces service-to-service authz for read calls to lending/ledger.
- **`anacredit.create` is HUMANS-ONLY (GHSA-58jq-9hq3-66jr, #4228).**
  `anacredit_rest_ext.rego`'s `operator-anacredit-create` now carries
  `not startswith(input.principal.id, "service-account-")`. Without it the rule was role-only, and
  `service-account-openbank-services` — the identity nearly every backend service authenticates
  as — carries ROLE_OPERATOR in the docker and CI realms and is classified HUMAN by
  `AuthorizeInterceptor`, so any backend service could feed the ECB regulatory return. Measured
  against `anacredit-opa-bundle.yaml` before the change it resolved exactly
  `["operator-anacredit-create"]`. The exclusion strands no caller: no REST client in the fleet
  targets this service, which the extension's own header had already asserted and #4228
  re-confirmed. `AUTHZ_ENFORCE=false` here, so the exposure was latent — the exclusion lands
  before the enforce flip rather than after it. Covered by `anacredit_rest_ext_test.rego`.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Caller claims to be reporting trigger when not authorised | OIDC + `@RolesAllowed(ROLE_ADMIN, ROLE_COMPLIANCE)` on all endpoints |
| **T**ampering | Alter credit amounts before ECB submission | Data sourced from ledger (immutable journal); checksummed submission; TLS to ECB; no writable path from external callers |
| **R**epudiation | Deny that a submission was sent | Submission ID + response stored in audit outbox; AuditEvent emitted per submission run |
| **I**nfo disclosure | Expose credit data via metrics or error responses | Low-cardinality metrics (ADR-0077/0079 — no party IDs/amounts in labels); error bodies contain only error codes, not raw data |
| **D**oS | Flood trigger endpoint | `@RolesAllowed` gate blocks unauthenticated; internal-only ingress (no Ingress resource); rate limit via platform Ingress controller |
| **E**oP | Escalate from reporting role to data modification | Read-only access to lending/ledger; no write path to those services; NetworkPolicy restricts egress |

## 5. Residual risks / assumptions

- **ECB credential rotation:** submission credentials in OpenBao must be rotated before expiry — manual process for now, tracked in the operational runbook.
- **Data completeness gate:** if lending-service returns partial data (circuit open), the service should skip and alarm rather than submit incomplete records — this is a compliance risk, currently mitigated by a health-check gate before submission.

## 6. Change log

- **2026-08-09** — `anacredit.create` narrowed to humans only (GHSA-58jq-9hq3-66jr, issue #4228);
  the extension also moved out of the generator heredoc into `anacredit_rest_ext.rego` so
  `opa-policy.yml`'s file-pair discovery can cover it — until then it had no test at all.
- **2026-06-19** — Initial lightweight threat model (ADR-0030 D2).
