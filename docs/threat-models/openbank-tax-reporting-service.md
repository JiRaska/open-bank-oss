# Threat model — openbank-tax-reporting-service

## 1. Scope & purpose

Statutory tax reporting (ADR-0180): the §38d withholding return (*vyúčtování daně vybírané
srážkou*). The service observes interest-service's withholding remittances, groups them into
monthly filing periods, lets an operator freeze a period (`OPEN → ASSEMBLED`) and record that the
return was submitted with its tax-office reference (`ASSEMBLED → FILED`). It moves no money and
posts nothing to the ledger; the money was withheld and remitted by interest-service. What it
produces is a figure a bank reports to the tax authority, so the asset at stake is the
**correctness and completeness of a filed tax return**.

ČNB pension-company returns additionally read the reporting legal entity's own ledger. The ledger
instance is separate from the bank's and from pension-fund assets. This service reads frozen
period evidence; it neither posts journal entries nor substitutes bank or fund balances.

Deployed for the first time by #5760. Not in `money_path_services`.

## 2. Data flow (DFD)

```
interest-service ──outbox──▶ openbank.interest.accrual.event ──(Kafka mTLS, own group)──▶ tax-reporting-service ──▶ tax-reporting-db (CNPG)
                                                                                                │      │
                                         openbank.dlq.tax-reporting.withholding-remitted-in ◀──┘      └── OPA sidecar (localhost:8181)
operator / auditor ──(OIDC)──▶ admin-ui ──(bearer)──▶ tax-reporting-service
tax-reporting-service ──(OIDC machine bearer; production mTLS prerequisite)──▶ pension-company ledger
```

Trust boundaries: Kafka (mTLS, the `tax-reporting-service` KafkaUser holds Read on the source topic
and its own consumer group, Write only on its own dead-letter topic); staff → service (in-cluster,
NetworkPolicy admits admin-ui and the platform scrapers only, no ingress); service → database
(single-owner CNPG cluster, backed up to S3 via Pod Identity); service → pension-company ledger
(a separate legal entity's financial evidence, with independently configured instance URL and
ledger authorization). This code PR does not prove deployment, credential projection or transport
reachability. Production named TLS configuration and the separate ledger deployment are supplied
by the follow-up #12500 and require rollout verification before company-books reporting is enabled.

## 3. Authn/Authz

- Every route is behind Keycloak OIDC (`@RolesAllowed`); there is no anonymous surface. The service
  validates inbound bearer tokens against the realm JWKS. Outbound `CompanyLedgerClient` carries
  `@OidcClientFilter`: it uses the configured tax-reporting machine client, not the staff caller's
  bearer identity. Its credential must be supplied through deployment secret configuration;
  this PR neither adds a credential nor proves that it is projected in a running deployment.
- The company ledger must authorize that machine for `ledger.read` on frozen evidence only.
  `PENSION_COMPANY_LEDGER_URL` must name the reporting company's instance, never the bank's.
  The #12500 follow-up binds this client to a named TLS bucket with CA trust, client certificate
  and hostname verification. That production transport is a prerequisite, not a verified rollout
  claim of this code PR.
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
| **Repudiation** | Dispute over who froze or filed a return | `assembledBy` / `filedBy` hold the authenticated subject, with timestamps, on the filing row |
| **Information disclosure** | Withholding amounts leak | Confidential classification. No ingress. Reads go to staff only. `tax.filing.inspect` is not a `.read`/`.list`, so neither generic read rule reaches it. The temporary old `.read` verb stays on the operator exclusion list; main #12488 also excludes service-accounts from compliance-read-any. Check both verbs with the generated bundle during rollout |
| **Denial of service / loss** | A database outage while remittances arrive | A failed write is retried, then rethrown. The channel's dead-letter strategy parks the record on its own DLQ topic. Acking it would silently understate the return. A dead-lettered record is still missing from that period's return until it is replayed, so it needs an operator |
| **Elevation of privilege** | A service-account holding `ROLE_OPERATOR` assembles, approves (as the four-eyes checker) or submits a ČNB statutory return | Non-read `tax.statutory-return.*` verbs, granted only to real staff by the extension; the resource also refuses a service-account token. `StatutoryReturnMachineActorIT` drives it over HTTP, `opa eval` against the generated bundle |
| **Elevation of privilege** | A backend service-account holding `ROLE_OPERATOR` assembles or files | Both lifecycle rules require a principal id outside `service-account-*`. Tested with that exact principal |
| **Tampering / wrong legal entity** | A company return uses bank or pension-fund books, live mutable totals, incomplete periods or another currency | Separate company-ledger URL and authorization are rollout prerequisites. `CompanyBooksCalculator` sums frozen YEAR evidence for prior years and MONTH evidence for the current year, requires a balanced flag, known account types and the reporting currency, and refuses any unavailable period. `CompanyBooksCalculatorTest` and `CompanyBooksGoldenIT` cover arithmetic, missing evidence and refusal paths |
| **Tampering / replay** | A pension participant total changes between capture and statutory assembly, or a repeated command reads a newer revision | The additive `source-assemblies` command scopes its durable successful idempotency receipt to the verified staff JWT issuer and subject. It verifies the provider's exact UTF-8 payload hash before decoding, and rejects a different company, period, currency or required cell set. The v2 return hash binds the source receipt and cell paths to the assembled values; an exact retry returns the original revision without a new provider capture. Rejected commands leave no successful-key receipt. The legacy assemble route and v1 hash remain supported but do not establish this source-close lineage |
| **Tampering / replay** | A fund return silently reads a newer NAV or a sealed payload with missing cells | Source assembly requires an explicit sealed `fundCloseId` from an authorized staff command and never seals or selects the latest close. The validated JWT subject must identify staff rather than an `agent:*` actor; the provider receipt ID, positive revision, schema and raw UTF-8 SHA-256 must agree before JSON decoding. Fund, period, CZK, closing NAV, source fingerprint and every mapped numeric cell must agree before insertion. The v3 return hash binds the selected close, receipt and sorted source paths; a verified-actor scoped successful key replays only the original intent. The legacy v1 assembly remains supported without this guarantee. A source failure or unknown cell refuses the new command without a return row |

## 5. Residual risks and follow-ups

- The additive participant source receipt is a technical snapshot, not a legal final close or an
  independent reconciliation. Existing legacy assemblies remain outside the new lineage guarantee;
  their migration or sunset and the authenticated provider deployment require separate proof.
- PSP31-04 source-aware assembly refuses before provider capture until a verified as-of beneficiary
  stock exists. The current pension instruction count cannot establish that reporting-date stock.
- The sealed fund close is a source snapshot for technical assembly, not independent regulatory
  reconciliation or evidence of regulator submission. Existing v1/v2 hash bytes are retained;
  migration or sunset of legacy fund assemblies and hosted provider rollout require separate proof.
  PSP40-01 uses the verified participant flow cells and an independently approved dividend input.

- Company-books reporting remains unavailable unless the books-opened date is configured. Before
  enabling it, verify the separate legal entity's complete frozen history, reporting currency,
  machine authorization and #12500's production mTLS settings. A wrong URL or opening date is an
  operator configuration risk; passing local stub tests does not establish live source identity.

- EPO XML rendering is not built (`UnavailableEpoRenderer`). An operator submits through the EPO
  portal or a data box and records the reference. The service reports this through
  `/export-capability` and does not imply it can render the file.
- There is no alert yet on the dead-letter topic's depth. One dead-lettered remittance means an
  understated return. That alert, and a replay procedure, belong with the first operational
  review after deploy.
