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

- Every route is behind Keycloak OIDC (`@RolesAllowed`); there is no anonymous surface. The service
  validates bearer tokens against the realm JWKS and holds **no client secret**: it calls nobody,
  so no credential is projected into its namespace.
- `@Authorize` on every route of `TaxFilingResource` asks the OPA sidecar.
  `tax_reporting_rest_ext.rego` grants `tax.filing.inspect` to real staff (operator, admin, auditor,
  viewer, compliance) and `tax.filing.assemble` / `tax.filing.file` to a real operator. Both rules
  exclude `service-account-*`, so the shared M2M account cannot read, freeze or file a return.
  The new read verb deliberately does not end in `.read`: it bypasses both generic read grants and
  is reachable only through the extension's rule. Main #12488 independently excludes
  service-accounts from `compliance-read-any`. Previously published tax images may still emit
  `tax.filing.read`, so the legacy verb remains excluded from `operator-read-any` and temporarily
  staff-only in the extension until those images have drained. Removing the exclusion first would
  reopen that old verb to shared service-accounts with `ROLE_OPERATOR` during rollout.
  No action sits in `rules.yaml: authz.role_action_matrix` (#3765/#3734).
- `StatutoryReturnResource` (ADR-0336 ČNB pension returns) carries `@Authorize` on every route:
  `tax.statutory-return.inspect` for the reads and `.assemble` / `.approve` / `.submit` for the
  lifecycle. The verbs are deliberately not `read`/`list`, so base `operator-read-any` and
  `compliance-read-any` (which match on those suffixes for any HUMAN-typed principal, the shared
  service-account included) can never grant them. The extension grants inspect to real staff and
  the lifecycle to a real operator, both excluding `service-account-*` and requiring a HUMAN
  principal (so no AI agent). Independently of OPA, the resource refuses a service-account token
  as maker, checker or submitter (`TaxReportingActor`: the `service-account-` username, the
  `service-account-<azp>` shape, or a `client_id` claim), and `StatutoryReturn.approve` refuses a
  checker who is the maker.
- `CorporateRegisterResource` (the operator-maintained figures feeding PSP 32-04, 50-04 and 40-01)
  follows the same pattern: `tax.corporate-register.inspect` for the reads (real staff only) and
  `.propose` / `.approve` / `.reject` for changes (a real operator only), all excluding
  `service-account-*` and requiring a HUMAN principal. The resource independently refuses a
  service-account token on both inspection routes and as maker or checker through the same
  claim-aware `TaxReportingActor`, including UUID principals and advisory OPA, and `CorporateRegisterEntry`
  refuses a checker who is the proposer.
- `AUTHZ_ENFORCE=true` from the first rollout. Every allow test has a matching must-deny.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **Spoofing** | A forged remittance event inflates or deflates a period | Kafka is mTLS-only. Only interest-service's KafkaUser holds Write on the source topic. The consumer acts only on records carrying the outbox relay's `ce-type` header |
| **Spoofing** | One person both freezes and files a return | `TaxFiling` refuses `markFiled` when the filer is the assembler (four-eyes). The rego grants neither transition to a service-account |
| **Tampering** | A redelivered or retried remittance is counted twice | `observe` is idempotent on the remittance id (the `duplicate` outcome) |
| **Tampering** | A period's totals change after it was frozen | `ASSEMBLED` freezes the totals. An illegal transition is a 409 (`TaxConflictException`) |
| **Tampering / information disclosure** | The pension participant source is read without its legal-entity scope, mixing another provider's figures into a ČNB return | PSP 31-04 passes the configured company UUID to pension-service. `OPENBANK_STATUTORY_RETURNS_COMPANY_ID` must match its configured provider UUID; the legacy `company` alias fails closed with 503. The consumer Pact pins the query and the provider's 400 response for a foreign UUID; the two services require a coordinated contract rollout before this return can assemble. |
| **Repudiation** | Dispute over who froze or filed a return | `assembledBy` / `filedBy` hold the authenticated subject, with timestamps, on the filing row |
| **Information disclosure** | Withholding amounts leak | Confidential classification. No ingress. Reads go to staff only. `tax.filing.inspect` is not a `.read`/`.list`, so neither generic read rule reaches it. The temporary old `.read` verb stays on the operator exclusion list; main #12488 also excludes service-accounts from compliance-read-any. Check both verbs with the generated bundle during rollout |
| **Denial of service / loss** | A database outage while remittances arrive | A failed write is retried, then rethrown. The channel's dead-letter strategy parks the record on its own DLQ topic. Acking it would silently understate the return. A dead-lettered record is still missing from that period's return until it is replayed, so it needs an operator |
| **Elevation of privilege** | A service-account holding `ROLE_OPERATOR` assembles, approves (as the four-eyes checker) or submits a ČNB statutory return | Non-read `tax.statutory-return.*` verbs, granted only to real staff by the extension; the resource also refuses a service-account token. `StatutoryReturnMachineActorIT` drives it over HTTP, `opa eval` against the generated bundle |
| **Elevation of privilege** | A backend service-account holding `ROLE_OPERATOR` assembles or files | Both lifecycle rules require a principal id outside `service-account-*`. Tested with that exact principal |

## 5. Residual risks and follow-ups

- EPO XML rendering is not built (`UnavailableEpoRenderer`). An operator submits through the EPO
  portal or a data box and records the reference. The service reports this through
  `/export-capability` and does not imply it can render the file.
- There is no alert yet on the dead-letter topic's depth. One dead-lettered remittance means an
  understated return. That alert, and a replay procedure, belong with the first operational
  review after deploy.
