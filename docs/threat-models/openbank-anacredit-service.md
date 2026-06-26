<!--
SPDX-License-Identifier: MPL-2.0
Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
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

- **2026-06-19** — Initial lightweight threat model (ADR-0030 D2).
