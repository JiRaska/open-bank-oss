<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-sdd-service

- **Date:** 2026-06-19
- **Status:** Lightweight STRIDE (ADR-0030 D2). **Money-path-adjacent** (mandate management, collection initiation).
- **Purpose:** SEPA Direct Debit — mandate lifecycle (B2C Core/B2B) and collection file generation.

## 1. Scope & purpose

The SDD service manages Direct Debit mandates (creation, amendment, cancellation) and generates
PAIN.008 collection instruction files for submission to the scheme. A mandate authorises the creditor
to debit the debtor's account. The actual fund movement is initiated via `transaction-service` (a
gated money-path service); SDD is **money-path-adjacent** — it authorises and shapes the collection
instruction, but the irreversible debit lives downstream.

## 2. Data flow (DFD)

```
[Customer Edge / Admin-UI] --HTTPS--> [openbank-sdd-service]
                                           |
                     mandate validation ---|---> [party-service] (debtor identity)
                     collection trigger ---|---> [transaction-service] (debit, money-path)
                     outbox events     ---|---> [Kafka: sdd.mandate.created, sdd.collection.initiated]
```

- **External entities:** customer-edge (authenticated), admin-UI (ROLE_OPERATOR).
- **Trust boundaries:** edge → service (OIDC + OPA); service → transaction-service (mTLS + OIDC, fail-closed).
- **Assets:** mandate data (IBAN, BIC, mandate reference, signed mandate PDF), collection schedule, debit amounts.

## 3. Authn/Authz

- All REST endpoints: `@RolesAllowed` (ROLE_CUSTOMER, ROLE_OPERATOR, ROLE_ADMIN scope-dependent, verified by `SddSecurityTest`).
- Collection initiation calls to `transaction-service` are service-to-service (OIDC client credentials, OPA policy).
- Mandate amendments require re-confirmation (SCA) for customer-initiated changes.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Rogue service initiates collection without valid mandate | Mandate reference validated before every collection; OIDC service identity on downstream calls; `@RolesAllowed` gate |
| **T**ampering | Alter collection amount or IBAN in flight | TLS in transit; amount/creditor stored in mandate record (immutable after signing); `X-Request-ID` idempotency on collection initiation |
| **R**epudiation | Debtor denies authorising mandate | Mandate signed at creation and stored; AuditEvent per mandate action; SCA evidence (ADR-0021) for customer-initiated mandates |
| **I**nfo disclosure | Expose mandate IBANs via error bodies or metrics | Error bodies contain codes only (no IBAN); metrics are low-cardinality (no IBAN/amount labels, ADR-0077/0079) |
| **D**oS | Flood mandate creation or collection trigger | `@RolesAllowed` gate; idempotency key guards duplicate collection; rate limit at ingress |
| **E**oP | Use mandate to debit more than authorised amount | Amount validated against mandate ceiling before collection; downstream transaction-service is authoritative amount boundary |

## 5. Residual risks / assumptions

- **Mandate PDF storage:** signed mandate PDFs stored in-cluster object store — access restricted to ROLE_OPERATOR/ADMIN; encryption at rest via CNPG volume encryption.
- **B2B mandate confirmation:** creditor-side B2B mandates skip the debtor pre-notification requirement — intentional per SEPA B2B rules; documented in the SDD operational runbook.
- **Collection file SFTP:** PAIN.008 files delivered to the scheme via SFTP; credentials in OpenBao. SFTP key rotation is a manual operational step.

## 6. Change log

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `accountId` on listMandates and `debitDate` on assessRefund. Both handlers are non-suspend and threw at the method boundary. `debitDate` is the input to the refund-window arithmetic (8-week / 13-month), so a request missing it must be rejected rather than defaulted — an UNPARSEABLE date is a different class and already 400 via `DateTimeExceptionMapper`. No new caller or boundary. Rollback: revert.
- **2026-06-19** — Initial lightweight threat model (ADR-0030 D2).
