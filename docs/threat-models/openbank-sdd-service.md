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
- **Trust boundaries:** edge → service (OIDC + OPA); service → transaction-service (mTLS + OIDC, fail-closed);
  service → `opa` PDP sidecar over loopback (`localhost:8181`, no cross-namespace ingress — the port name
  `opa` is in `gen-network-policies.py`'s `SIDECAR_LOCAL_ONLY_PORT_NAMES`, so it is not published to sdd's
  caller set). Residual, and shared with every service that runs this sidecar: the unconditional
  same-namespace NetworkPolicy rule still reaches 8181 from co-tenant pods, because NetworkPolicy cannot
  express "loopback only".
- **Assets:** mandate data (IBAN, BIC, mandate reference, signed mandate PDF), collection schedule, debit amounts.

## 3. Authn/Authz

- All REST endpoints: `@RolesAllowed` (ROLE_OPERATOR, ROLE_ADMIN, ROLE_PAYMENTS, ROLE_API; reads also
  ROLE_VIEWER), verified by `SddSecurityTest`.
- Fine-grained authorization: ten `@Authorize` sites over seven actions (`sdd.create`, `.list`, `.read`,
  `.approve`, `.update`, `.delete`, `.authorise`), evaluated by the `opa` PDP sidecar against
  `data.openbank.rest.allow` — base `rest.rego` plus `sdd_rest_ext.rego`.
- **`AUTHZ_ENFORCE` is `false` — advisory. Decisions are evaluated and logged; none are blocked.** Until it
  is flipped, the `@Authorize` layer is evidence, not a control, and `@RolesAllowed` plus the customer-edge
  ownership (IDOR) check are the only enforcing gates. Tracked in #3679; the flip needs the live advisory
  decision log to show an empty would-DENY population for real callers.
- Policy shape (`sdd_rest_ext.rego`): `operator-sdd-write` excludes `service-account-*` identities outright
  — the shared `openbank-services` client carries ROLE_OPERATOR and every `client_credentials` JWT is
  classified HUMAN, so a role-only write rule would be a fleet-wide mandate-write primitive.
  `edge-service-sdd` names `service-account-openbank-edge` and enumerates only the five actions the customer
  app has a route for; `sdd.approve` (bank-side B2B verification) and `sdd.authorise` (the decision that
  books the debit) are withheld from it. `sdd.authorise` has no M2M rule at all — no caller for it exists.
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

- **2026-09-03** — Four-eyes assessment (#8359, ADR-0034 D-criteria as applied in the #938 sweep).
  Per-verb caller audit over all seven actions: **`sdd.approve` (B2B mandate confirmation) is now
  four-eyes-gated** via `rules.yaml: four_eyes.actions` — the confirmation authorises every future
  collection against the debtor, and its caller set is human-only (the edge grant deliberately
  excludes it; `operator-sdd-write` excludes every `service-account-*`). Exact action, not the
  `approve` verb: the verb would also flag `lending.approve` (already maker-checker in-app) and pull
  non-money-path `dispatch.approve` into the grantable gate. **`sdd.authorise` assessed,
  NOT gated:** it is the bulk authorisation of inbound collections whose designed caller is clearing
  automation (not built yet — `sdd_rest_ext.rego` header), so gating it today covers no real caller
  and plants the trap the `four_eyes.verbs` guardrail describes. When that caller ships, the operator
  authorise-by-hand path gets its own distinct action and that is gated instead.
  `sdd.create/update/delete` are edge-reachable customer self-service with effect on future debits
  only — ungateable per the guardrail. Four-eyes enforcement itself remains off fleet-wide
  (`authz.four-eyes.enforce`, ADR-0155) — the wiring sets the decision flag, nothing pauses yet.
  Correction: §3's "`AUTHZ_ENFORCE` is `false` — advisory" was stale — flipped to `true` on
  2026-08-10 (#4427); the `@Authorize` layer is an enforcing control today.

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal transaction REST edge through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or payment-control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `accountId` on listMandates and `debitDate` on assessRefund. Both handlers are non-suspend and threw at the method boundary. `debitDate` is the input to the refund-window arithmetic (8-week / 13-month), so a request missing it must be rejected rather than defaulted — an UNPARSEABLE date is a different class and already 400 via `DateTimeExceptionMapper`. No new caller or boundary. Rollback: revert.
- **2026-06-19** — Initial lightweight threat model (ADR-0030 D2).
- **2026-08-06** — OPA PDP sidecar bootstrapped (#3679/#3807). Section 3 corrected: it had claimed
  `@RolesAllowed(ROLE_CUSTOMER, …)` (a role this resource never lists) and section 2 claimed an
  `edge → service (OIDC + OPA)` boundary while **no PDP sidecar and no policy bundle existed for this
  service at all** — so every `@Authorize` annotation was inert and there was not even a decision to log.
  The sidecar, the bundle and `sdd_rest_ext.rego` now exist; the loopback PDP boundary is recorded in
  section 2 and the authorization model in section 3. `AUTHZ_ENFORCE` remains `false`, deliberately.
