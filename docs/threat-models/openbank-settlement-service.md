# Threat Model — openbank-settlement-service (ADR-0101 Temporal P3)

Date: 2026-06-19
Status: Current
Author(s): OpenBank platform
Reviewed by: (pending 2nd approval — money-path rule)

---

## Service overview

`openbank-settlement-service` orchestrates the three-step money movement for interbank and
intraday settlement: **debit payer → credit payee → book to ledger**. It is the root cause
origin of the settlement money-bug (found 2026-06-19) and the primary motivation for ADR-0101
Temporal migration: the hand-rolled saga had no explicit intermediate-state timeout, allowing
the saga to hang indefinitely without compensation.

This document covers the Temporal-based replacement workflow and its security posture.

---

## Assets

| Asset | Sensitivity | Owner |
|-------|-------------|-------|
| Settlement DB (CNPG `settlement-db`) | HIGH — holds debit/credit state | platform |
| Temporal workflow history (namespace `openbank-settlement`) | HIGH — full execution trace | platform |
| Payer account balance (via `balance-service` port) | CRITICAL | balance-service |
| Payee account balance | CRITICAL | balance-service |
| Ledger entries (via `ledger-service` port) | CRITICAL | ledger-service |

---

## Trust boundary

```
[caller] ──HTTPS (OIDC)──▶ POST /api/v1/settlements ─▶ [settlement-service] ─▶ [settlements DB]
                                          │ originate → settle
                                          ▼
[Temporal server] ──gRPC (no transport auth)──▶ [settlement-service worker]
                                          │
                                ┌─────────┼──────────┐
                                ▼         ▼          ▼
                          debit-port  credit-port  ledger-port
                          (balance)   (balance)   (ledger)
```

*(Corrected 2026-08-27, #6066: this edge previously read `gRPC (mTLS)`, contradicting the S1
row below, which was corrected on 2026-08-20 by #6055 and states that it is not mTLS. The
diagram is now consistent with S1 — `TemporalClientProducer` builds
`WorkflowServiceStubsOptions` with a target and an optional metrics scope and nothing else, the
Temporal HelmRelease configures no frontend TLS, and no API key or namespace token is
configured anywhere, so the edge carries no transport authentication at all rather than merely
no mTLS. What does constrain it is stated in S1 and Residual risk 2.)*

The settlement-service worker receives workflow tasks from Temporal over gRPC. The worker calls
three downstream ports. Each port call is an activity — idempotency-keyed so Temporal can replay
without double-execution.

**Origination boundary (added with DB persistence + REST origination).** `POST /api/v1/settlements`
creates a PENDING settlement and starts its workflow. It is a money-movement trigger, so it is
gated by `@RolesAllowed(SERVICE, OPERATOR, ADMIN)` (coarse) **and** the fine-grained
`settlement.create` OPA action (advisory until the settlement OPA policy lands — see Residual
risks). Settlements are now persisted in the `settlements` table (DB-backed Panache repository,
replacing the former in-memory stub), so settlement state is durable across restarts and auditable.

---

## Threat enumeration (STRIDE)

### S — Spoofing

| ID | Threat | Mitigation |
|----|--------|------------|
| S1 | Attacker impersonates Temporal server, injects malicious workflow tasks | **Corrected 2026-08-20 (#6055) — neither control named here exists; see Residual risk 2.** `OpaActivityInterceptor` is present in no Kotlin source in this repository, and the worker↔frontend connection is not mTLS: `TemporalClientProducer` (`openbank-libs-temporal`, the single fleet-wide producer) builds `WorkflowServiceStubsOptions` with a target and nothing else — no SSL context — and the Temporal HelmRelease configures no TLS either. What actually constrains this edge: the `temporal-platform-ingress` NetworkPolicy admits port 7233 only from an explicit namespace allow-list, and the Temporal SDK dispatches only the activity types the worker registered (`SettlementActivitiesImpl`), so an unregistered activity name is not dispatchable at all. Transport authentication on this edge is unbuilt fleet-wide and tracked in #6066. |
| S2 | Attacker impersonates settlement-service to call balance/ledger | Corrected 2026-08-16 (#3921) — see note below. `debit-port`/`credit-port`/`ledger-port` are `OidcClientRequestReactiveFilter`-backed REST clients (`BalanceRestClient`, `LedgerRestClient`) that attach a client-credentials bearer token from `quarkus.oidc-client`, the confidential `openbank-services` Keycloak client (`OIDC_CLIENT_SECRET` Vault-projected, never in git); OPA authz on the balance/ledger REST receivers checks that identity |

### T — Tampering

| ID | Threat | Mitigation |
|----|--------|------------|
| T1 | Replayed debit or reversal activity moves money twice | Corrected 2026-08-20 (#6037). The reference id is **not** `workflowRunId + activityId` as this row previously claimed — that would change on every workflow run and defeat the guard. It is a pure function of the settlement id (`settlement-debit-<id>`, `settlement-credit-<id>`, and for compensation `settlement-debit-reversal-<id>` / `settlement-credit-reversal-<id>`), so every Temporal retry re-sends an identical key. balance-service deduplicates durably on the `balance_movement` primary key `(account_id, currency, reference_id, operation)`, applying mutation and marker in one transaction and answering a duplicate 200 with `applied = false`. The reversal namespace is deliberately distinct from the forward one rather than relying on `operation` alone to separate the rows. |
| T2 | Settlement DB record modified out-of-band (bypassing workflow) | Postgres row-level security; audit trail in `openbank-libs/audit` |
| T3 | Temporal workflow history modified | Temporal's history is append-only; CNPG backups every 30 days |

### R — Repudiation

| ID | Threat | Mitigation |
|----|--------|------------|
| R1 | Settlement denied by payer ("I never authorised this") | Temporal workflow execution ID links to the initiating payment ID in `sepa-payment-service` / `domestic-payment-service`; full chain queryable from admin UI |
| R2 | Compensation claimed to have run when it did not | **This threat was realised, not mitigated, from ADR-0101 P3 until #6037.** The cited mitigation — "compensation activities update status to `REVERSED`/`CREDITED_REVERSED`/`LEDGER_REVERSED` atomically" — *was* the defect: updating the status column was the **only** thing `reverseDebit`/`reverseCredit`/`reverseBookToLedger` did. They logged `"stub: wire reversal to balance-service"` and returned success, so the status row asserted an unwind that had moved no money. Now: the two balance reversals issue real counter-movements to balance-service before writing a status, a refused reversal is recorded as `REVERSAL_FAILED` (not as success), and the unimplemented ledger reversal throws a non-retryable `ApplicationFailure` and records `LEDGER_REVERSAL_UNSUPPORTED`. Verified by `SettlementReversalIT`, which asserts the outbound HTTP request over real HTTP and the resulting row over plain JDBC — a mocked port cannot tell a counterparty call from a no-op. |

### I — Information disclosure

| ID | Threat | Mitigation |
|----|--------|------------|
| I1 | Temporal workflow history contains PII (account IDs, amounts) | Namespace `openbank-settlement` restricted to internal OIDC audience (ADR-0056); GDPR retention policy 90 days |
| I2 | Logs leak account numbers | Loggers use settlementId (UUID), not account numbers |

### D — Denial of service

| ID | Threat | Mitigation |
|----|--------|------------|
| D1 | Temporal server unavailable, blocks all settlement | Feature flag `openbank.temporal.enabled` — legacy path coexists; flip to false for emergency fallback |
| D2 | Activity storm (mass settlement retry) | `scheduleToCloseTimeout=2h`, `maxAttempts=5`, exponential backoff — bounded blast radius |

### E — Elevation of privilege

| ID | Threat | Mitigation |
|----|--------|------------|
| E1 | Attacker injects a workflow that calls `reverseBookToLedger` on a legitimate settlement | **Corrected 2026-08-20 (#6055) — no activity-level authorization exists; see Residual risk 2.** No policy gate mediates activity dispatch, and no `compensation=true` context is ever evaluated — the word `compensation` appeared nowhere in the policy file that this row cited. The residual constraints are structural, not authorization: only the seven methods registered on the worker are dispatchable, and `reverseBookToLedger` is not implemented — it throws a non-retryable failure and records `LEDGER_REVERSAL_UNSUPPORTED` (#6037), so it moves no money whoever invokes it. Ordering (that a compensation runs only after its forward leg) is a property of `SettlementWorkflowImpl`'s saga, which registers each compensation after the corresponding forward activity returns — it is not enforced by any external policy. |
| E2 | Service account token used to submit arbitrary workflows | Temporal namespace ACL restricts task queue submission to settlement-service service account (SPIFFE `spiffe://openbank/ns/openbank-settlement/sa/settlement-service`) |

---

## Residual risks

0. **Persistence is now DB-backed (was an in-memory stub).** `SettlementRepositoryImpl` previously
   stored settlements in a `ConcurrentHashMap`; it is now a hibernate-reactive Panache repository on
   the `settlements` table. Settlement state survives restarts and is auditable. The real
   balance/ledger adapters landed earlier (ADR-0101 #1522), so a settlement originated via
   `POST /api/v1/settlements` with Temporal enabled does move real money end-to-end. **The
   origination endpoint is the new highest-value attack surface** — keep its role gate + OPA action
   tight, and never expose it without authentication.

1. ~~**OPA policy for `settlement.create` (origination) not yet written.**~~ **Closed** (ADR-0034
   Phase 5, issue #266). `settlement_rest_ext.rego` now grants `operator-settlement-write` for
   HUMAN ROLE_OPERATOR/ROLE_ADMIN on `settlement.create`; there is deliberately NO SERVICE/M2M allow
   rule — a fleet audit found no in-repo caller ever invokes `POST /api/v1/settlements` as a
   service-to-service action (no rest-client config anywhere in the repo targets it, and the
   NetworkPolicy ingress-allow-list only admits admin-ui, which forwards the operator's own bearer
   token). `AUTHZ_ENFORCE=true` is now set on the settlement Rollout; the OPA sidecar is deployed.
   `@RolesAllowed(SERVICE, OPERATOR, ADMIN)` remains the coarse outer gate (unchanged, pre-existing),
   so a SERVICE-token caller would still pass RBAC but is denied by OPA (deny-by-default, no
   matching `allowed_reasons`) — if a genuine M2M caller for this action is ever verified, add a
   narrow `service-settlement-m2m` rule the way `service-domestic-payment-m2m` / `service-sca-m2m`
   do it, not a blanket SERVICE allow.

2. **OPEN — there is no activity-level authorization on the settlement worker, and this entry was
   wrongly marked Closed (#6055).** The previous text ("`settlement_activity.rego` exists and is bundled
   (`settlement-opa-bundle.yaml`), so `OpaActivityInterceptor` has a real policy to evaluate") was false in
   all three of its parts, and every part was measured before this correction:

   - **No interceptor.** `OpaActivityInterceptor` occurs in no Kotlin source anywhere in the repository.
     `openbank-libs-temporal` contains only `TemporalClientProducer` and `TemporalConfig`; the
     "OPA activity interceptor" listed in ADR-0101's P0 row was never built. `SettlementWorkerRegistrar`
     registers workflow and activity implementations and installs no interceptor.
   - **Not bundled.** `settlement-opa-bundle.yaml` carries exactly `rest.rego`,
     `settlement_rest_ext.rego`, `agents.rego`, `agents-data.yaml`, `rules-data.yaml` and
     `manifest.json` — the generator (`gen-settlement-opa-bundle.sh`) never reads the file. Materialising
     the ConfigMap into the sidecar's directory layout and evaluating it confirms this from the outside:
     `data.openbank.settlement` resolves to `{}`, i.e. the package is not in the deployed bundle at all.
     Controls in the same run: `settlement.create` for a HUMAN `ROLE_OPERATOR` allows with reason
     `operator-settlement-write`, and the same action for an ANONYMOUS principal denies — so the probe
     was live and the empty result is absence, not a broken query.
   - **Not queried, and it did not say what E1 said.** The service's only OPA query path is
     `/v1/data/openbank/rest/allow` (`application.yaml`), which cannot reach an activity package. And the
     policy's `allow` was a single membership test of `input.activity` against a fixed set in which
     `reverseBookToLedger` was an unconditional member: evaluated directly, `reverseBookToLedger` with
     `compensation: false` returned `true`. It could not have enforced E1 even if something had loaded it.

   **Decision: the control is not being built, and the orphan policy file is deleted rather than left
   reading as coverage.** The rationale is that the gate as specified would add no security property the
   service does not already have. The Temporal SDK dispatches only registered activity types, and the
   policy's allowed set was exactly the seven methods the worker registers — so the membership test
   restated the registration and could never deny anything reachable. The threat S1 actually describes (a
   spoofed or compromised Temporal frontend) is a transport-authentication problem: a task forged by such
   a peer carries a legitimate activity name, so no name-based policy distinguishes it. The genuinely
   security-relevant constraint in E1 — that a compensation may run only after its forward leg — is a saga
   state invariant belonging in `SettlementWorkflowImpl`, not in an external stateless policy that cannot
   see the workflow's history. `settlement_activity.rego` was the only `src/main/resources/opa/` file in
   the fleet; every other service's policy lives in its bundle generator, so it was an orphan by
   convention as well as by wiring.

   **What remains open, and is tracked rather than closed here:** transport authentication on the
   worker↔Temporal-frontend edge (S1's other clause, unbuilt fleet-wide — the shared client configures no
   SSL context; filed as #6066), and the ledger-reversal/maker-checker questions raised by
   #6037's `LEDGER_REVERSAL_UNSUPPORTED` path (numbering of residual risks is in flux while #6048 is
   open, so this cites the issue rather than an index). This entry stays OPEN until the S1 edge
   is decided. It is deliberately **not** re-closed by this change: nothing was built here, only a false
   claim withdrawn.

3. **Temporal is now the SOLE orchestrator (issue #1917, ADR-0120 Phase 6).** The
   `openbank.temporal.enabled` dispatch gate is removed and the in-process legacy saga
   (`legacySettle`) is deleted. This closes a compensation gap: the legacy path flipped a mid-flight
   failure straight to `REJECTED` **without reversing an already-moved debit/credit**, whereas
   `SettlementWorkflow` runs `reverseBookToLedger → reverseCredit → reverseDebit` before rejecting.
   **Correction (#6037):** that last sentence was true only of the ordering. Until #6037 all three
   compensation activities were stubs, so the Temporal path unwound exactly as much money as the
   legacy path it replaced — none — while recording that it had. The gap is closed for the two
   balance movements as of #6037; the ledger half remains open, as risk 5 below.
   Flip pre-conditions are verified: Temporal server healthy, `openbank-settlement` namespace
   registered, `settlement_activity.rego` present (risk 2). Worker registration is gated on
   `openbank.settlement.worker.enabled` (default true; false in `%test`). **Cutover risk:** with no
   fallback path, a settlement cannot be processed if the Temporal worker is not registered or the
   frontend is unreachable — a sandbox canary must confirm the worker registers and a real settlement
   drives `PENDING → BOOKED` before merge, and the deterministic simulation (ADR-0100/0115) stays green.

5. **A settlement that fails after `bookToLedger` leaves the GL entry standing (issue #6037).**
   `reverseBookToLedger` is deliberately NOT implemented and fails loudly
   (`LEDGER_REVERSAL_UNSUPPORTED` + a non-retryable `ApplicationFailure`) rather than reporting a
   reversal it did not perform. ledger-service does expose `POST /api/v1/journals/{journalId}/reverse`,
   but three things must be decided before settlement-service may call it: (a) `ledger.reverse` is
   maker-checker gated, so a service-account call queues for approval instead of posting, and granting
   a machine that action is a `rules.yaml: shared_m2m_matrix_write_grants` decision — an automatic GL
   reversal driven by a failed saga is close to what maker-checker exists to prevent; (b) settlement
   does not retain the journal id (`bookToLedger` discards the response), so it must be persisted or
   re-resolved via `GET /api/v1/journals/transaction/{transactionId}`; (c) the endpoint answers 409
   when the entry's fiscal period is ATTESTED, and the accounting convention there is to correct
   forward in the open period, which is a different posting. **Operational consequence:** such a
   settlement unwinds both balance movements and needs a manual correcting entry in the general
   ledger. It is visible as a `LEDGER_REVERSAL_UNSUPPORTED` row and an ERROR log line, and is
   announced at boot by `SettlementCompensationCapabilities`.

6. **A reversal can be legitimately refused and the money stays moved (issue #6037).**
   balance-service enforces `booked - amount >= overdraftFloor` on every debit, so if the payee has
   already moved the credited funds out, `reverseCredit` is refused with 422 and no retry resolves
   it. The row records `REVERSAL_FAILED` and the audit event carries `AuditResult.FAILURE`.
   Recovering those funds is a collections/dispute process, not an API call — this is a real
   property of the world, and the design records it rather than smoothing it into a success.

   Recording it is not the same as seeing it, and for a time it was only recorded:
   `SettlementStrandedGauge` published a **hand-kept** list of non-terminal statuses written before
   #6037, so `REVERSAL_FAILED` and `LEDGER_REVERSAL_UNSUPPORTED` had no age series at all and
   neither `SettlementStrandedMidSaga` nor `SettlementStuckAfterCompensation` could fire for them —
   the two states meaning *the money did not come back* were the two nothing could observe. The
   list is now derived from `SettlementStatus`, `LEDGER_REVERSAL_UNSUPPORTED` joins the
   `SettlementStuckAfterCompensation` warning, and `REVERSAL_FAILED` has its own **critical**
   `SettlementReversalFailed` rule — separated because the other rule's premise ("funds are safe,
   only the record is wrong") is false here.

   Nor was it enough to publish the state: `SettlementWorkflowImpl` called `rejectSettlement`
   unconditionally after the compensation loop, so the row was overwritten to `REJECTED` within
   seconds — a terminal status asserting a clean unwind while funds were outstanding, and a row
   the age gauge never saw. `REJECTED` is now written **only when every compensation succeeded**
   (#6286); a settlement whose compensation was refused rests in `REVERSAL_FAILED`, non-terminal
   and alerting, until the collections/dispute path resolves it. The residual risk that remains is
   that this resolution is an operator action with no workflow behind it, so the alert stays lit
   for as long as the obligation does — deliberately.

7. **A debit applied by balance-service but not observed by settlement-service is not compensated
   (pre-existing, not addressed by #6037).** `SettlementWorkflowImpl` registers the `reverseDebit`
   compensation only *after* `debitPayer` returns, so a debit that balance-service applied while the
   response was lost — or that succeeded before the subsequent status update failed — leaves money
   moved with no compensation registered. The idempotent `referenceId` means a retry does not
   double-debit, and the row is left non-terminal (visible to the `SettlementStuckAfterCompensation`
   alert in #6036), but no automatic unwind occurs. Closing this needs the compensation registered
   before the call, which changes the saga's shape.

4. **2-approval gate** — money-path rule requires 2 approvals. This PR has 1 (automated review).
   Second approval from a human committer required before merge per rules.yaml.

5. **Both outbound money-path edges were pointed at unresolvable hostnames for the life of the
   service (#3931).** The Rollout carried no `BALANCE_SERVICE_URL` / `LEDGER_SERVICE_URL`, so the
   debit, credit and ledger-book adapters (`BalanceDebitAdapter`, `BalanceCreditAdapter`,
   `LedgerBookAdapter`) used `application.yaml`'s defaults `http://openbank-balance-service:8080`
   and `http://openbank-ledger-service:8080`. No Service of either name is declared in any
   namespace, and the ports were wrong for the real Services too (`balance-service:8103` in
   `balances`, `ledger-service:8101` in `ledger`). **This is an availability, not a confidentiality
   or integrity, finding**: the trust boundary itself did not move — the same two edges to the same
   two services, over the same NetworkPolicy (`payments` was already permitted to both) — the
   requests simply never left the pod. It does not change any STRIDE row above; every mitigation
   listed for S2/T1/R2 applies unchanged once the calls actually arrive.

   Worth recording as a residual risk because of *how it survived*: no test layer in this repo can
   observe a hostname (the unit tests stub both ports, an IT serves localhost, a consumer pact
   answers whatever path the client asks for), and the failure mode of a settlement whose debit
   activity cannot connect is a Temporal retry loop, not a visible integrity breach. The
   `incluster-hostname-resolution` gate is the only control that can see it, and it is enforced as
   of #3931. Config is now the fleet shape: localhost dev default in `application.yaml`, real URL
   from the workload env in gitops.

---

## DORA / PSD2 / PCI alignment

- **DORA Art. 11** — Temporal workflow history = reconstructable execution trace for every settlement.
- **DORA Art. 17** — DST (ADR-0100) can replay the workflow deterministically for ICT testing evidence.
- **PSD2 Art. 5(3)** — payment authentication context preserved across any Temporal restart.
- **PCI DSS Req. 10** — activity history + `openbank-libs/audit` hash chain = tamper-evident audit log.

---

## References

- ADR-0030 (threat model policy)
- ADR-0034 (OPA unified authz) — applies to this service at the **REST** layer only
  (`settlement.create`). The `OpaActivityInterceptor` this line used to name does not exist (#6055).
- ADR-0101 (Temporal durable execution — this migration)
- Settlement money-bug (2026-06-19, root cause: missing intermediate-state timeout in hand-rolled saga)

## Change log

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing balance and ledger REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or settlement-control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-20** (#6055) — **S1, E1 and residual risk 2 credited an OPA activity-authorization control
  that has never existed, and risk 2 was signed off as Closed on it.** `OpaActivityInterceptor` is in no
  Kotlin source; the policy file the entry cited was in no bundle ConfigMap, was reachable from no query
  path, and did not contain the `compensation` term E1 attributed to it. Measured by materialising
  `settlement-opa-bundle.yaml` into the sidecar layout and evaluating it — `data.openbank.settlement` is
  `{}`, against a must-ALLOW and a must-DENY control on `settlement.create` in the same run. Same shape as
  #3921's service-mesh correction on S2 in this very document: a mitigation cell naming a mechanism that
  was never deployed here. Risk 2 is **re-opened**, not rewritten to match the absent code; the orphan
  `settlement_activity.rego` is deleted; S1's mTLS clause is corrected too, since verifying the row
  surfaced that the shared `TemporalClientProducer` configures no SSL context anywhere in the fleet — that
  half is filed as #6066 rather than resolved here.

- **2026-08-20** (#6037) — **The compensation path did not reverse money.** `reverseDebit`,
  `reverseCredit` and `reverseBookToLedger` wrote a status row, logged
  `"stub: wire reversal to balance-service"`, and reported success; no call reached balance-service
  or ledger-service. R2 above named this exact threat and listed the stub as its mitigation, and
  residual risk 3 credited the Temporal migration with closing a compensation gap it had not closed.
  T1's stated idempotency key (`workflowRunId + activityId`) was also not the one the code sends,
  and would not have worked if it were — it changes per run. Both rows and risk 3 are corrected
  above; risks 5-7 are added for what remains open. Reachability was measured before deciding
  urgency: the live sandbox `settlements` table held **zero rows** with `n_tup_ins = 0` and no
  statistics reset, so the compensation path has never run there and no money is currently
  unreturned. The defect was pre-deployment, not an incident.

- **2026-08-16** (#3921) — S2's mitigation was corrected and the outbound OIDC client was
  configured for the first time; both belong together. The prior text credited "Service mesh mTLS
  (SPIFFE identity)" for the debit/credit/ledger port calls — no service mesh is deployed anywhere
  in this platform, so that line described a control that does not exist. The real mechanism is
  `quarkus.oidc-client` client-credentials on each `OidcClientRequest*Filter`-backed REST client,
  and `application.yaml` had **no `quarkus.oidc-client` block at all** — only `quarkus.oidc`
  (inbound; validates tokens arriving at settlement-service). So the actual state before this fix
  was neither mesh mTLS nor OIDC: every settlement→balance and settlement→ledger call left with no
  `Authorization` header, and the callee's 401 (not 403 — no token to be missing a role) was the
  only signal. Fixed by adding the block with `auth-server-url:
  ${QUARKUS_OIDC_AUTH_SERVER_URL:http://localhost:8080/realms/openbank}`, the same variable the
  inbound block already reads and the deployed workload already sets — no gitops change needed.
  **No new trust boundary**: this restores the M2M identity the ports were always meant to
  present, on the existing edges, against the existing confidential client. Enforced fleet-wide by
  `check-oidc-client-configured.py` (six services fixed; ledger and settlement money-path).
