# Threat model — openbank-tax-reporting-service

## 1. Scope & purpose

Statutory tax reporting (ADR-0180): the §38d withholding return (*vyúčtování daně vybírané
srážkou*). The service observes interest-service's withholding remittances, groups them into
monthly filing periods, lets an operator freeze a period (`OPEN → ASSEMBLED`) and record that the
return was submitted with its tax-office reference (`ASSEMBLED → FILED`). It moves no money and
posts nothing to the ledger; the money was withheld and remitted by interest-service. What it
produces is a figure a bank reports to the tax authority, so the asset at stake is the
**correctness and completeness of a filed tax return**.

Deployed for the first time by #5760. Not in `money_path_services`.

## 2. Data flow (DFD)

```
interest-service ──outbox──▶ openbank.interest.accrual.event ──(Kafka mTLS, own group)──▶ tax-reporting-service ──▶ tax-reporting-db (CNPG)
                                                                                                │      │
                                         openbank.dlq.tax-reporting.withholding-remitted-in ◀──┘      └── OPA sidecar (localhost:8181)
operator / auditor ──(OIDC)──▶ admin-ui ──(bearer)──▶ tax-reporting-service
```

Trust boundaries: Kafka (mTLS, the `tax-reporting-service` KafkaUser holds Read on the source topic
and its own consumer group, Write only on its own dead-letter topic); staff → service (in-cluster,
NetworkPolicy admits admin-ui and the platform scrapers only, no ingress); service → database
(single-owner CNPG cluster, backed up to S3 via Pod Identity).

## 3. Authn/Authz

- Every route is behind Keycloak OIDC (`@RolesAllowed`); there is no anonymous surface.
- `@Authorize` on every route of `TaxFilingResource` asks the OPA sidecar.
  `tax_reporting_rest_ext.rego` grants `tax.filing.read` to real staff (operator, admin, auditor,
  viewer, compliance) and `tax.filing.assemble` / `tax.filing.file` to a real operator. Both rules
  exclude `service-account-*`, so the shared M2M account holding `ROLE_OPERATOR` cannot freeze or
  file a return. No action sits in `rules.yaml: authz.role_action_matrix` (#3765/#3734).
- `AUTHZ_ENFORCE=true` from the first rollout. Every allow test has a matching must-deny.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **Spoofing** | A forged remittance event inflates or deflates a period | Kafka is mTLS-only. Only interest-service's KafkaUser holds Write on the source topic. The consumer acts only on records carrying the outbox relay's `ce-type` header |
| **Spoofing** | One person both freezes and files a return | `TaxFiling` refuses `markFiled` when the filer is the assembler (four-eyes). The rego grants neither transition to a service-account |
| **Tampering** | A redelivered or retried remittance is counted twice | `observe` is idempotent on the remittance id (the `duplicate` outcome) |
| **Tampering** | A period's totals change after it was frozen | `ASSEMBLED` freezes the totals. An illegal transition is a 409 (`TaxConflictException`) |
| **Repudiation** | Dispute over who froze or filed a return | `assembledBy` / `filedBy` hold the authenticated subject, with timestamps, on the filing row |
| **Information disclosure** | Withholding amounts leak | Confidential classification. No ingress. Reads go to staff only. Service-accounts are excluded by the extension (a service-account with `ROLE_OPERATOR` can still read through base `rest.rego`'s operator read rule; that is fleet policy, not this service's) |
| **Denial of service / loss** | A database outage while remittances arrive | A failed write is retried, then rethrown. The channel's dead-letter strategy parks the record on its own DLQ topic. Acking it would silently understate the return. A dead-lettered record is still missing from that period's return until it is replayed, so it needs an operator |
| **Elevation of privilege** | A backend service-account holding `ROLE_OPERATOR` assembles or files | Both lifecycle rules require a principal id outside `service-account-*`. Tested with that exact principal |

## 5. Residual risks and follow-ups

- EPO XML rendering is not built (`UnavailableEpoRenderer`). An operator submits through the EPO
  portal or a data box and records the reference. The service reports this through
  `/export-capability` and does not imply it can render the file.
- There is no alert yet on the dead-letter topic's depth. One dead-lettered remittance means an
  understated return. That alert, and a replay procedure, belong with the first operational
  review after deploy.
- Statutory-return slices (ADR-0336, PSP/PEF pension returns) add routes. Each new route needs
  `@Authorize` and an extension rule before this model's §3 holds for it.
