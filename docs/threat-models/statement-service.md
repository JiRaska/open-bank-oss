# Threat model — Statement Service (ADR-0035 / ADR-0248)

**Surface:** `openbank-statement-service` — owns the per-pocket statement lifecycle (period-close,
fail-closed reconciliation, on-demand camt.053/MT940/PDF render, ad-hoc export) and, as of ADR-0248,
a synchronous customer-facing styled-document download that calls out to document-service.
**Posture:** NOT on the `money_path_services` list (`rules.yaml`), so the 2-approval / money-path
regime does not apply — but ADR-0248 is a **trust-boundary change** regardless: it adds a new
outbound synchronous call into document-service, statement-service's first (`rules.yaml:
trust_boundary_diff_change`), which is why this document exists.

## Assets
- **Statement correctness** — every rendered format (camt.053, MT940, PDF, and now the styled
  document) is a pure projection of the same `StatementModel`; a wrong figure on any of them is a
  regulatory (PSD2 Art. 58(2)) and customer-trust failure.
- **Legal/electronic sequence integrity** — the per-pocket sequence assigned at close is the
  document's identity; it must never be reused, skipped, or assignable by an unauthenticated caller.
- **Fail-closed reconciliation boundary** — a period whose computed closing balance disagrees with
  balance-service's independently-reported one must never close (`ReconciliationException` → 409).
- **Booked-entry / balance confidentiality** — every render path (existing and new) exposes an
  account's transaction history and balances; unauthorized access is a PII/financial-data leak.

## Trust boundaries
- Caller (operator / in-cluster service / customer-facing edge, OIDC bearer,
  `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_AUDITOR`/`ROLE_API`) → statement-service REST
  (`/api/v1/statements/**`).
- statement-service → transaction-service (booked entries), balance-service (reconciliation
  closing balance), account-service + party-service (pocket identity, holder name) — all pre-existing
  reactive REST-client reads (`infrastructure/client/RestClients.kt`).
- statement-service → the scheduled period-close job (`ClosePeriodUseCase`, self-healing catch-up,
  ADR-0069 D3) — an in-process trigger, no external trust boundary of its own.
- **NEW (ADR-0248): statement-service → document-service**, synchronous, on customer download
  request only. Calls `GET /api/v1/documents/templates` (list, to resolve the PUBLISHED
  `MESICNI_VYPIS_CS`/`MESICNI_VYPIS_EN` template body — there is no get-by-code route) then
  `POST /api/v1/documents/templates/preview` (merge `StatementModel`-derived data into that body).
  Both calls are non-persisting on the document-service side (ADR-0248) — the response is streamed
  straight back to the caller and never stored or cached by statement-service either.

## Threats & mitigations (STRIDE)
| Threat | Mitigation |
| --- | --- |
| **Spoofing the caller** | Every `/api/v1/statements/**` endpoint (including the new `/document` one) requires a valid OIDC bearer via the class-level `@RolesAllowed`; unauthenticated calls are 401. `StatementSecurityTest` guards against an unannotated endpoint. |
| **Tampering — wrong statement served for the wrong account** | `#accountId`/`#currency`/`#legalSequence` are path parameters resolved against `StatementPeriodRepository.findBySequence`, which looks the closed period up by exactly that composite key; there is no ambient/session-derived account context to confuse. The new `/document` endpoint reuses the identical lookup (`StatementModelUseCase.statementModel`, factored out of `RenderStatementUseCase.render` specifically so both paths share one reconciliation/lookup implementation rather than risk drifting). |
| **Tampering — a malicious/buggy caller requests another customer's statement** | **Gap, not new**: `@Authorize(action = "statement.read", resource = "#accountId")` is enforced at the OPA policy layer, but `rules.yaml`'s `role_action_matrix` grants `statement.read` to `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_COMPLIANCE`/`ROLE_API` — all machine/staff roles — with no per-request check that the caller's own identity owns `accountId`. Statement-service trusts its caller (ADR-0248: "the customer-facing edge calling it") to have already scoped the request to the authenticated customer's own account, the same design `render()` already relies on. The new `/document` endpoint inherits this exactly — it adds no new exposure, but it does add a second callable surface with the same gap, which is worth closing once (e.g. an OPA rule comparing `input.principal.id`'s linked party/account set against `accountId`) rather than twice. |
| **Tampering — SSTI via the Handlebars data map** | Every value statement-service sends into `document.*`/`party.*`/`account.*` is a projection of `StatementModel` (booked entries, balances, IBAN, holder name) — no caller-supplied string reaches document-service's template engine as anything but a data value; the template body itself is document-service's own, published-only, and out of statement-service's control (document-service's own threat model owns SSTI on the engine side). |
| **Repudiation** | Period-close and restatement already emit outbox events (`account.statement.period.closed.v1`) atomically with the persisted record. The new download path renders and discards — it is read-only and mints no new record, so it has nothing to repudiate; it does not need (and per ADR-0248 must not have) an audit trail beyond normal access logging. |
| **Information disclosure** | Rendered output (all formats, including the new styled document) carries booked-entry detail and balances — the same class of data `render()`/`export()` already expose. The new call to document-service travels over the same in-cluster mTLS/OIDC path as the other four outbound REST clients (transaction/balance/account/party); no new plaintext egress. |
| **Information disclosure — PII moved from transient to AT REST (#3986)** | The close-time render snapshot (`statement_period.model_snapshot`, Flyway V7) exists so a closed statement re-renders byte-identically, and that necessarily means retaining what the statement said: **IBAN, holder name and booked line-item descriptions are now stored for 10 years** in `openbank_statement`, where previously they lived only in memory during a render. No new data *kind* and no new egress — the same fields already left the service in every rendered document and in the `account.statement.period.closed.v1` outbox payload, same controller, intra-OpenBank — and the table's existing classification already governs it (`compliance` / `restricted` / 10y, `governance.yaml` unchanged). What changes is the blast radius of a database compromise or an over-broad read: a dump of `statement_period` is now customer-identifying on its own rather than a table of pseudonymous account UUIDs and amounts. Mitigated by the pre-existing controls on that datastore (no new access path is introduced; the column is read only by the render path, which is already behind `@RolesAllowed` + `@Authorize`). |
| **Denial of service — the new outbound call degrades only this one endpoint** | `DocumentTemplateRestAdapter` wraps every failure (list call, preview call, template not found) in a single `DocumentServiceException`, mapped by `StatementResource.document()` to `502 Bad Gateway`. A slow or unreachable document-service therefore fails closed on `/document` only — period-close, camt.053/MT940/PDF render, ad-hoc export, list, and restate are all on independent code paths and untouched. There is no retry/circuit-breaker on this new client today (matching the existing four REST clients, none of which have one either) — a sustained document-service outage means every `/document` call times out at the HTTP client default rather than failing fast; a tracked follow-up if this endpoint's traffic grows past occasional customer downloads. |
| **Elevation / cluster pivot** | Unchanged posture: restricted PSS, non-root, OPA authz (`@Authorize`, ADR-0034), and an egress NetworkPolicy that must now also allow statement-service → document-service (previously not a peer) — `openbank-infra` gitops owns that allow-list and needs the corresponding entry when this ships (out of scope for this PR: `openbank-infra/` is untouched here). |

## Residual risk / follow-ups
- **Account-ownership check is missing at the statement-service layer, fleet-wide, not just on the
  new endpoint** (see the Tampering row above). Tracked as a gap to close once, not per-endpoint.
- **GDPR erasure/export must now reach `statement_period.model_snapshot` (#3986).** Any subject-rights
  routine that previously treated this service as holding no direct identifiers is out of date: the
  snapshot column holds IBAN, holder name and line-item descriptions. Note the tension this creates
  and do not resolve it silently — the same column is the *reason* a closed statement is reproducible
  under PSD2 Art. 58(2), and the record carries a 10y ČNB/AML retention obligation, so an erasure
  request against a closed period is a legal-basis question (retention obligation vs Art. 17), not a
  DELETE. Naming it here rather than deciding it in this PR.
- **Pre-V7 periods still render from live data.** Periods closed before the snapshot existed have
  none, are deliberately not backfilled (a backfill would freeze whatever drift has already happened
  and stamp it as the issued document), and therefore keep the original #3986 defect until they age
  out of retention or are restated. The fallback logs a warning per render; nothing alerts on it, and
  no inventory of affected rows has been taken — that count is a `SELECT count(*) FROM
  statement_period WHERE model_snapshot IS NULL` away and is the natural first step of the follow-up.
- **No circuit breaker / timeout tuning on the new document-service client** — it inherits whatever
  the shared Quarkus REST-client default connect/read timeout is, same as the other four adapters in
  `infrastructure/client/`. Worth revisiting together, not singled out for this one client.
- **The rendered "document" is HTML, not PDF, today.** document-service's non-persisting `preview`
  endpoint (`PreviewTemplateRequest`/`PreviewTemplateResponse`) merges Handlebars data into a
  template body and returns `renderedHtml` — it does not run the HTML→PDF step that the persisting
  `/api/v1/documents/render` endpoint does. ADR-0248 deliberately excludes `/render` from this flow
  (it persists a `Document` row + outbox event, which the ADR rejects for a customer-download click).
  So today's `/document` endpoint streams back `text/html` with the `MESICNI_VYPIS_CS`/`_EN` body
  merged in; a byte-identical PDF requires either a PDF-capable variant of `preview` in
  document-service or a client-side HTML→PDF step — both out of scope for `openbank-statement-service/`
  and left as an explicit follow-up, not silently deferred.
- **Template existence is not verified at deploy time.** `DocumentTemplateRestAdapter` fails a single
  request with `502` if `MESICNI_VYPIS_CS`/`MESICNI_VYPIS_EN` is not yet PUBLISHED in document-service
  (e.g. before the seed from the companion ADR-0248 work lands) — there is no boot-time check. This is
  consistent with how the four existing REST-client dependencies are treated (no boot-time reachability
  probe either) but is worth naming explicitly since it is a new, request-time-only failure mode.
