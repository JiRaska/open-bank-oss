# Threat model — openbank-delegation-service (ADR-0030, ADR-0232)

Status: draft for first deploy. Money-path-adjacent: the service mints payment rights,
so abuse cases are authorization failures, not data-loss failures.

Rows T1, T2, T4 and T8 were rewritten after review found they described mitigations the code did
not implement — the T1 and T2 claims were not merely incomplete, they were the reasoning that let
an account-takeover chain and an unlimited-SCA-replay ship as "mitigated". Where a gap remains it
is now stated in the row rather than implied away.

## Assets

- `DelegationGrant` records — who may do what on whose resources (confidential; contract
  evidence under the ADR-0118 retention schedule). Since issue #3604 a grant also carries the two
  counterparty **display names**, snapshotted at offer time from the eligibility lookup — so this
  service now holds personal names at rest, not only party ids. Nothing else about the party is
  stored: the pid client materialises `givenName`/`familyName` and drops the rest of
  `coreAttributes` (birthdate, birth number, documents, nationality) at the parser.
- Enforcement integrity of the whole platform: every product service's delegation
  projection trusts this service's event stream.
- SCA ceremony integrity (grant + acceptance).
- `ExternalDisclosure` evidence — a short-lived, bounded release of one sealed document to an
  external recipient. It holds only recipient label, document/delegation ids, state timestamps and
  domain-separated SHA-256 hashes of the link secret and OTP; it never stores either raw secret.

## Lifecycle approval execution

Bank-side lifecycle proposals bind to the exact `lifecycleRevision` observed by the maker. An
approval is executed only while the proposal row and referenced grant are locked, the observed
revision still matches, and the status transition remains legal. The grant CAS, lifecycle outbox
event and immutable `EXECUTED` evidence commit in one transaction. A stale or pre-revision legacy
proposal remains readable evidence but is never executed. Mutation endpoints remain default-off
and require a human operator plus OPA; client applications do not receive a bank-side override.

## Trust boundaries

1. Customer edge → REST API (OIDC JWT, coarse `ROLE_API` + OPA sidecar per ADR-0034).
   Admitted at the network layer by delegation's generated ingress policy, which lists
   `customer-edge` on 8126 only because customer-edge's gitops env declares the URL
   (#4248). Before that the boundary existed in code and not in any policy, so the hop was
   dropped rather than authenticated — a closed door is not the same control as a checked one,
   and the failure looked like a timeout instead of a 403.
2. delegation-service → sca-service / pid-service (REST, fail-closed on outage).
3. delegation-service → Kafka (`openbank.delegation.events`) → product-service
   projections (ADR-0232 D3).
4. Customer edge → reservation API (`/delegations/{id}/reservations`, ADR-0249 D3) — a new inbound
   surface on boundary 1, and the first one on the synchronous path of a payment: if it is down or
   slow, a delegated payment must fail rather than proceed uncounted.
5. Admin UI → role-preset API (`/delegation-role-presets`) — operator/admin reads and admin-only
   writes over the existing OIDC boundary. Presets are configuration for future offers: grants copy
   their capabilities and never retain a mutable reference to a preset.
6. delegation-service → compacted Kafka (`openbank.delegation.spend-reservation-state`) — complete
   domestic reservation snapshots. The stream is default-off for new domestic reservations until
   a compatible binding consumer is deployed; rail-neutral callers remain unchanged.
7. delegation-service → party-service (`https://party-service.party.svc:8443`) — authoritative
   principal status/type and live representation mandates. Mutual TLS uses the platform private CA
   with per-service client identity and hostname verification; OIDC authenticates the application
   principal and the network policy admits only the declared namespace/port edge.
8. External recipient → customer-edge → delegation-service disclosure endpoint. The public edge
   owns anonymous ingress and its per-IP ingress rate limit; it authenticates upstream using only
   its M2M identity. OPA permits precisely `delegation.disclosure.verify` and
   `delegation.disclosure.release` for that identity, never the shared backend client. Every
   unavailable state is a uniform 404 and the only successful payload is a sealed PDF derivative.

## Threats and mitigations

| # | Threat | Mitigation |
|---|--------|-----------|
| T1 | Grantor offers a grant for a resource they do not own | **Offer-time ownership gate** (`ResourceOwnershipClient`): the grantor's ownership of ACCOUNT/SAVINGS_GOAL is confirmed against account-service and of CARD against card-issuance-service; UNVERIFIABLE (outage, or an object type with no lookup wired) refuses the offer. The previous mitigation was **wrong**: it claimed a product service's `owner OR grant` check would reject a foreign resource id, but that check is a DISJUNCTION — an active grant row is authority by itself, and the projection carries no grantor to compare. Two consenting parties could therefore mint payment rights over a third party's account with nothing but their own valid SCA. Residual: the consuming projection still does not compare the grant's grantor to the resource owner (defence in depth, tracked for the account/card projection PRs). |
| T2 | SCA ceremony replay / cross-party substitution | Challenge must be COMPLETED, purpose-bound (`DELEGATION_GRANT`/`DELEGATION_ACCEPT`) and party-bound; accept requires the *grantee's* challenge — **and the challenge is then SPENT** through sca-service's compare-and-consume gate (RTS Art. 5 single-use, atomic on `consumedAt`). Reading `status == COMPLETED` alone did not mitigate replay at all, which this row previously claimed: completion is a fact that stays true, so one ceremony authorised unlimited grants of arbitrary scope. Residual: the challenge is not dynamic-linked to the grant's *content* (resource, capabilities, ceilings) — that needs a delegation-shaped `DynamicLinkingData` in sca-service. |
| T3 | Eligibility bypass (un-KYC'd or sanctioned grantee) | Fail-closed eligibility gate at offer (D5); KycLevel.FULL for execution capabilities. Follow-up: continuous re-screening on party status change events → auto-suspend. |
| T4 | Repudiation ("I never granted that") | Both ceremonies SCA-bound with session ids persisted on the aggregate and single-use; every transition emits an outbox event; `closedBy` is now derived from the authenticated caller, not from a query parameter the caller chose. **Gap, stated honestly:** ADR-0133 tamper-evident audit-chain threading is NOT implemented — this service publishes no audit envelope at all. This row previously cited it as a mitigation. Tracked with the money-leg/onBehalfOf slice. |
| T5 | Outbox/event forgery feeding product projections | Kafka mTLS (ADR-0137 pattern); projections key on aggregate id and re-check status transitions monotonically. |
| T6 | Revocation delay (eventual consistency) | Documented seconds-level propagation (ADR-0232 Negative); fraud suspend hook consumes the same topic; grantor UX states the window honestly. |
| T7 | Idempotency replay duplicating offers | `X-Request-ID` idempotency store (Redis) on POST /delegations. |
| T8 | Excessive grant harvesting (`/grantor`, `/grantee` lists, `GET /{id}`) | Every customer-facing handler is scoped by `X-Customer-Party-Id` — the party customer-edge authenticated, the one identity in the request the caller cannot choose. A list for another party is 403; a grant the caller is not party to is **404, not 403**, so the endpoint is not an existence oracle. `delegation_rest_ext.rego` grants the customer actions to the edge principal only, and excludes `service-account-*` from the operator write rule (the shared `openbank-services` identity carries ROLE_OPERATOR in the realm — a role-only rule would hand every backend service the power to mint or revoke payment rights). `/check` returns a decision, never the grant. |
| T9 | Panache/native-SQL injection in claim query | Static SQL constants, bound parameters only (fleet-wide #1201 pattern). |
| T10 | Object-level grant used as execution channel | Aggregate invariant: EXECUTION capabilities rejected on object resource types; object grants are read-only disclosure. |
| T11 | Unauthorized revoke / forged revoker | `revokedBy` is derived: a customer revoke acts as the party the edge authenticated and must be the grantor; the query parameter survives only on the role-gated bank path. Previously it was a required, trusted query parameter that both authorised the act and wrote the audit field, so any authenticated caller could revoke any grant and sign it as somebody else. `suspend`/`reinstate` are additionally narrowed to ROLE_OPERATOR/ROLE_ADMIN and reject a customer-scoped call. |
| T12 | A grant outliving its `validTo` in the product projections | Hourly expiry sweep flips ACTIVE → EXPIRED and enqueues `DelegationExpired`, so projections close their rows. The sweep is a `suspend fun` (rules.yaml: scheduled_methods): as a plain `@Scheduled` method it ran with no Vert.x context, so every reactive Panache call threw HR000068 into a single ERROR line and the sweep had **never expired a grant**. A grant past `validTo` still reads as inactive to anything that computes it, so the exposure was the projections that need the event. |
| T13 | Counterparty display name used as a party-name oracle, or leaked to a non-party (issue #3604) | The name is **snapshotted onto the grant at offer time**, never resolved at read time, so no lookup surface is created and customer-edge gains **no new authority** — its party OPA grants stay `party.consent.update` + `party:resolve` (the ADR-0072 blind-index dedup gate, not an id lookup). It rides the existing `DelegationResponse`, which T8 already scopes: a customer sees only grants they are grantor or grantee of, a list for another party is 403, and a grant the caller is not party to is 404. Both parties are by construction already identified to each other by the grant itself, so the name discloses nothing the relationship does not. The two alternatives were rejected for exactly this row: granting the edge `party.read` would let it read ANY party (the usage is scoped, the *grant* is what gets reviewed), and a dedicated display-name endpoint would create a new lookup surface for a value that does not need to be live. Residual: role-gated bank staff (`ROLE_OPERATOR`/`ROLE_ADMIN`) can read grants for any party and now see the names too — unchanged in scope from the ids they already saw, but a wider PII surface; and a snapshot goes stale on a legitimate rename, which is deliberate (an authorisation record states what was true at consent) but means the label is not an identity assertion. |
| T14 | Delegated spend exceeding the grantor's ceiling (ADR-0249 D3) | **Reserve before the money moves, in one place.** `POST /delegations/{id}/reservations` counts the amount against the daily and monthly ceilings inside a transaction that opens with `SELECT id FROM delegation_grants WHERE id = ? FOR UPDATE`, so Postgres serialises concurrent reserves against one grant and count-then-insert is indivisible. Two simultaneous payments can no longer both pass a check neither passes alone — the failure mode that made counting-after-settlement not a control. The window is `AccountingClock.BANK_ZONE`, not UTC, so a delegate cannot spend a full daily ceiling at local midnight and another an hour later. Per D5 a grant with `ACCOUNT_INITIATE_PAYMENT` and no ceiling at all is refused at creation. Residual: the counter is authoritative only for rails that ask it. ATM withdrawal does not, which is why D5 refuses delegated cash — and any future rail that spends a grant without reserving reintroduces exactly the gap this row closes. |
| T15 | Reservation abuse: replay inflating the counter, or a leaked reservation released by a third party | Replay is a **database** fact, not a read-then-write: a unique index on `(grant_id, idempotency_key)` returns the original reservation, so a retried payment never takes the headroom twice. Every reservation endpoint is scoped by the same `X-Customer-Party-Id` as T8 and must belong to a grant the caller is party to; an unknown reservation is 404, not 403. Residual, stated rather than implied: **reserve/confirm/release emit no audit event yet** (the T4 gap, unchanged) and a leaked reservation shrinks a delegate's own headroom until it expires — a denial-of-headroom, not a spend. |
| T16 | A lower-privileged operator changes a preset to widen future grants, or an edit silently widens existing grants | The resource allows ROLE_OPERATOR/ROLE_ADMIN reads but narrows POST/PUT/DELETE to ROLE_ADMIN. The domain validates every capability against the closed resource-capability matrix and rejects empty roles. Existing grants contain their own copied capability set, so editing or deleting a preset cannot change live authority. The expanded account/card vocabulary remains deny-by-default at consumers: a product action must ask `/check` for its exact capability; a broader-looking role name is not authority by itself. Residual: preset changes are authenticated and persisted but do not yet emit a dedicated audit envelope; database audit/retention for this configuration surface must land before treating it as a regulated approval record. |
| T17 | A delayed activation/reinstatement event reopens a revoked, suspended or expired grant; concurrent writers overwrite a newer lifecycle state | `delegation_grants.lifecycle_revision` is monotonic and database-owned. A `BEFORE UPDATE` trigger serialises every writer, enforces the legal status graph, and increments exactly once. New application writes additionally compare-and-set the revision and update only lifecycle-owned columns, never merge a detached aggregate. An old rolling-deployment image remains compatible for acyclic transitions, but its revisionless `SUSPENDED → ACTIVE` is refused: that state can cycle, so accepting it would let a stale pre-cycle reinstatement undo a newer fraud suspension (ABA). A DEFERRABLE outbox trigger stamps the committed revision after all statements, so old Hibernate ordering (`outbox INSERT` before grant `UPDATE`) cannot publish the previous value. Account/card consumers must deploy first with durable per-grant cursors: only a strictly newer revised event mutates authority, revisionless opens are ignored, and revisionless closes create a legacy tombstone. Residual: a legacy tombstone cannot be safely ordered and therefore requires explicit reconciliation or reissue; availability is sacrificed rather than guessing that a later open is safe. |
| T18 | A reservation is created without recoverable state, a stale grant is used after revoke, or a delayed publish makes a rebuild reopen headroom | The grant is re-read under `PESSIMISTIC_WRITE` in the same transaction as ceiling evaluation, reservation insertion and outbox insertion. New DOMESTIC_PAYMENT admission is default-off unless the state writer is enabled; exact retries remain replayable, while a key reused for another immutable tuple is 409. The compacted stream publishes no raw idempotency key and uses bounded `reservationId:v1`/`:v2` keys, so an ambiguous delayed v1 completion cannot replace terminal v2. Consumers must fold the greatest payload revision. Rollback must first stop creators and prove zero RESERVED domestic rows plus zero unsent state rows; turning the writer off alone is unsafe. |
| T19 | A service account, maker, or replay decides a bank-side lifecycle proposal | Proposal/decision actions are human-only and exclude `service-account-*`; the domain rejects maker = checker. The proposal request key is unique in Postgres and terminal rejection is serialized by a row lock, preserving the original actor, reason and timestamps. The admin BFF exposes GET only. Residual: direct staff lifecycle endpoints are not routed through the inbox, so mutation activation remains prohibited until that authority is narrowed. |
| T20 | Approval races a newer lifecycle transition and overwrites state or emits stale evidence | This first slice is fail-closed: `approve=true` returns 409 even if the dark mutation setting is enabled, so no grant row or outbox event is touched. Execution may land only on top of lifecycle V8-V10 through their expected-revision/CAS transition and revision-stamped event, proven by a real-Postgres race test. Emergency suspend remains only the existing fraud/AML safety path. |
| T21 | Stale business mandate, a foreign account, or a guessed portfolio id widens an active profile's account scope | `customer-edge` resolves `X-Acting-For` against party-service on every request and forwards a business profile only after an ACTIVE mandate check; absent that header, the active profile is the token party. Portfolio create/list/get additionally require the authenticated active profile to equal `ownerPartyId`; the customer API does not accept an owner field at all, and a supplied one is refused before the upstream call. Before persistence, every account is checked against account-service's authoritative owner; a foreign account returns 422 and an unavailable lookup returns retryable 503 without storing the portfolio. A mismatched or absent principal is 403, including before an idempotency replay can reveal a cached response. A guessed detail id remains a 404 at the customer boundary. The aggregate requires a non-empty, bounded account set, so it cannot become a client-only "all accounts" selector. A portfolio is explicitly **not** a grant, N-of-M decision, or payment authorization; no payment rail reads it until a later enforcing producer/consumer slice exists. Residual: ownership changes after creation require revalidation when a later grant binds or uses the portfolio; co-signing is not yet built and cannot be represented as active authority. |
| T24 | A caller forges an entity profile, reuses a revoked mandate, or intercepts the authority lookup | The edge derives the human actor from the authenticated token; it is not accepted from the app. delegation-service re-checks the selected principal and the actor's currently active mandate at issuance time against party-service, before consuming SCA. Unknown, inactive, malformed and unavailable results fail closed. The new east-west call uses party-service's parallel private-CA mTLS listener on 8443: hostname verification authenticates the server, a namespace-local cert identifies delegation-service, and TLS 1.3 is pinned; OIDC and namespace/port NetworkPolicy remain independent controls. An event-only projection was rejected for this admission decision because bootstrap/replay lag and a revocation race would trade authorization freshness for availability. Residual: this establishes statutory/owner representation, not a delegated employee capability such as `delegation.manage`. |
| T22 | Product service releases an operation under a weaker policy because the grant event discarded its approval policy | `DelegationOffered`, `DelegationActivated` and `DelegationReinstated` carry `approvalPolicy` and `requiredApprovals`; the account consumer contract proves exact N-of-M projection and legacy-without-fields → SOLO. Non-SOLO offer remains fail-closed until the eligible-member snapshot and atomic decision ledger land. Rollout is consumer-first; producer-first would create a promise the enforcer cannot yet retain. |
| T23 | Product service releases an operation under a weaker policy because the grant event discarded its approval policy | `DelegationOffered`, `DelegationActivated` and `DelegationReinstated` carry `approvalPolicy` and `requiredApprovals`; the account consumer contract proves exact N-of-M projection and legacy-without-fields → SOLO. Non-SOLO offer remains fail-closed until the eligible-member snapshot and atomic decision ledger land. Rollout is consumer-first; producer-first would create a promise the enforcer cannot yet retain. |
| T28 | A leaked external-link token becomes an unbounded, permanent document download | Each disclosure has a per-record SHA-256 link-secret hash, hard expiry, revocable state and a positive maximum view count. The row and its immutable view timestamps are locked and transitioned in one database transaction, so replicas cannot both consume the last allowed view. Raw secrets never persist. customer-edge maps every 4xx to the same unavailable response and is behind its per-IP ingress limit. |
| T29 | A link alone releases a confidential document | The model requires a separate OTP hash before any view may be consumed. Invalid attempts are persisted and permanently lock the disclosure at five failures; OTP verification, revocation, expiry and view-limit denial occur before document bytes are requested. The raw OTP is generated only at issuance and never persists. Residual: an out-of-band delivery adapter remains a rollout dependency, not an API fallback. |
| T30 | A concurrent view and revocation allow a post-revocation release | Issuance, OTP verification, view consumption and revocation are expressed as transitions on one disclosure aggregate. The repository takes `SELECT … FOR UPDATE` on the exact disclosure before executing a transition, records at most one append-only view timestamp and updates the count in that transaction. A request that locks after revocation sees unavailable and cannot issue bytes. The exporter is called before the final CAS so an outage cannot burn a view; only the CAS winner receives a derived sealed artifact, never document-service's internal original `/content`. |
| T31 | A customer shares a document they are not entitled to disclose, or shares a broad live API | The intended issuer path will re-read the linked grant, require `ACTIVE`, require the authenticated grantor and require `DOCUMENT` + `OBJECT_READ`; D7 is an immutable single-object emission rather than a new product-service authorization path. Residual: this command/API is not yet implemented, so no production control should claim these checks have run. |
| T32 | Disclosure evidence exposes unnecessary recipient or device data | The storage schema deliberately retains one human recipient label and the event time required for grantor transparency, but not recipient account identity, IP address, user-agent, raw link or raw OTP. View rows are append-only so a later counter update cannot erase earlier access evidence. Residual: retention, data-subject access and audit-envelope routing must be specified with the sealed-export integration before external exposure. |

## Outbound authentication (added 2026-08-06)

Every REST client this service owns — sca-service, pid-service, party-service, account-service,
card-issuance —
carries the shared `openbank-services` client-credentials token via
`OidcClientRequestReactiveFilter`. Before the outbound-authentication fix, the original four called
out with **no Authorization header**
and every one 401'd, so the service could not complete a single ceremony: offers refused with the
ownership gate's `UNVERIFIABLE`, accepts never reached the SCA read.

Two things worth carrying from how that was found and what it means:

- **A fail-closed verdict names the CHECK, not the CAUSE.** The offer reported "ownership of
  ACCOUNT/… could not be established", which is literally true and completely misleading: the
  account belonged to the grantor and the real error was `Unauthorized, status code 401` in the pod
  log. Any `UNVERIFIABLE` here should be read as "the check could not run", and the first question
  is whether the call was even authenticated.
- **`quarkus.oidc` and `quarkus.oidc-client` are different things with confusingly similar names.**
  The first validates bearers this service RECEIVES and mints nothing; only the second produces an
  outbound token. Having the first configured is what made the omission invisible.

Residual: the token is the SHARED `openbank-services` identity, which carries ROLE_OPERATOR
fleet-wide. delegation-service's outbound calls are therefore indistinguishable at the callee from
any other backend service's — the same pre-existing fleet design point recorded in the account OPA
bundle, not introduced here. It is why the callee-side rules gate on action, not merely on role.

No test could see this: every test mocks the client interface, so nothing exercises the wire. The
gap closes only with a consumer pact or a run against a deployed stack.

## Out of scope (tracked as follow-ups)

- Exposure-shaped object disclosure (D7b): **fail closed.** New non-null `exposure` is refused
  with `EXPOSURE_UNSUPPORTED`, before SCA or persistence. Historical rows remain readable for
  audit but are excluded from the authorization decision and shared-document list. This stays the
  rule until delivery creates a transformed artifact and atomically enforces redaction, watermark,
  download policy and view counting; proxying original object bytes would not implement any of them.
- D7b sealed-document exporter: redaction/watermark rendering, institutional PAdES seal, OTP
  delivery/attempt throttling, recipient-facing rate limit and audit-envelope routing. The
  disclosure persistence boundary is implemented, but **no external ingress is enabled** until
  this export path and its controls ship together.
- EUDI verifiable-credential delivery channel (follow-up ADR on ADR-0094).
- `AccountAuthorization` migration dual-run window (two grant sources for accounts).
- ~~**Cumulative daily/monthly ceilings are REFUSED at the API, not silently accepted.**~~
  **Superseded 2026-08-08 by ADR-0249 D3** — see T14/T15 above and the change-log entry. The
  reasoning below is kept because it is the argument that decided the shape of the fix: a counter
  needs a point where spend is observed, so the fix had to create one (reserve-then-confirm) rather
  than add a counter to a service that never sees the money. The stated residual still stands:
  rows created before #3613 keep ceilings that went uncounted, and finding those grantors is work
  that has not been done.

  The
  aggregate still carries the fields and `DelegationOffered` still publishes them, but no
  projection counts spend against them, so they were never a control. A request supplying
  `dailyLimit` or `monthlyLimit` is now rejected `400 CUMULATIVE_LIMIT_UNSUPPORTED` at
  delegation-service and at customer-edge, so a grantor cannot be shown "max 5 000 Kč/den" and
  believe they capped their delegate. Only `perTransactionLimit` binds.

  The refusal is placed AHEAD of the SCA gate: a doomed request must not spend the customer's
  single-use challenge. Rows created before this change keep their unenforced ceilings and still
  serialize — each is a grant a customer accepted under a false belief, and finding those grantors
  is work that has not been done.

  Enforcing them was rejected on evidence, not effort: a counter needs a point where spend is
  observed, and there is none. `isAuthorizedForAmount` has no production caller, no money-moving
  service reads a delegation grant, and `DelegationOffered` does not carry the figure to a
  projection that could count against it. A counter shipped today would sit at zero and read as
  implemented — the same false promise, harder to notice.
- **No notification on any lifecycle transition** (ADR-0232 D4 requires both parties be told).
- **No sanctions/PEP screening at grant time** (ADR-0232 D5); the eligibility gate checks party
  status and KYC level only.
- **LEGAL_ENTITY grantors are bound to a human actor** (ADR-0232 D5 / ADR-0284): customer-edge
  derives `X-Customer-Actor-Party-Id` from the authenticated token while keeping the selected
  entity in `X-Customer-Party-Id`; delegation-service resolves the principal type in party-service
  and requires that human to appear in its active `acting-for` mandate set. The same human owns
  the consumed grant SCA challenge. Missing identity, a revoked/expired mandate, a non-active
  principal, malformed data or either lookup being unavailable refuses preview and offer before
  SCA is spent. Retail remains the degenerate case actor == principal. Residual: this proves a
  statutory/owner mandate, not an employee delegation carrying `delegation.manage`; employee-level
  sub-administration remains a later, explicitly capability-scoped grant.

## Change log

- **2026-09-07** — Role-preset creation is now replay-safe (#8351, ADR-0292). A retried
  `POST /api/v1/delegation-role-presets` stacked a duplicate catalog row; `create` now checks the
  admin-supplied natural key (name, resourceType) first and replays the original, with
  `uq_delegation_role_presets_name_type` (V15) as the race backstop. The grant-offer idempotency
  (Berlin Group `X-Request-ID`, already enforced) is recorded in ADR-0292. No new caller, endpoint
  or privilege. Rollback: revert + `DROP INDEX uq_delegation_role_presets_name_type`.

- **2026-09-03** — `authz.enforce` now defaults to **true** in `application.yaml` (#3679). Until
  now it read `${AUTHZ_ENFORCE:false}`, so enforcement was a property of one gitops manifest
  rather than of the service: the deployed Rollout sets the variable to `"true"`, so the cluster
  was enforcing, but **any environment that does not set the variable ran this money-path service
  in advisory mode** — a new cluster, a local run, an ad-hoc container, a restored namespace. In
  advisory mode `AuthorizeInterceptor` evaluates every `@Authorize` decision, logs the deny at
  WARN and lets the request through, so the failure is silent — and for this service that means
  the boundary in row 1 above degrades from a checked door to an observed one: every scoping rule
  that keeps `edge-service-delegation` off the bank-side `suspend`/`reinstate` actions, and
  `service-delegation-check` down to a single read-only action, is **inert** there. This closes
  that gap at the source. **No new caller, endpoint, network edge or grant** — the change can only
  ever move a request from allowed-while-denied to denied. Grantability was measured before
  flipping, since enforcing an action that no reason grants turns it into a 403: all 14 gated
  actions were evaluated with `opa eval` against the deployed bundle ConfigMap, each probe
  carrying a must-DENY and a must-ALLOW control, and every one resolves `allow=true` for at least
  one real principal. Residual: an operator running this service with no OPA sidecar reachable now
  gets 503 rather than an unauthorized success — the intended fail-closed direction, but it makes
  a missing sidecar a hard outage instead of a silent control bypass. Rollback: set
  `AUTHZ_ENFORCE=false` in the environment, which needs no code change.

- **2026-09-03** — Four-eyes remains **off**, recorded here because it is a different control from
  the one above and was verified rather than assumed (#3679). Measured against the deployed
  bundle, `delegation.revoke` and `delegation.reserve.release` DO evaluate
  `four_eyes_required=true` — both end in a verb listed in `rules.yaml: four_eyes.verbs`
  (`revoke`, `release`) and this service is in `money_path_services`; the other twelve actions are
  false. But **no `ApprovalStore` bean is wired in this service**, so
  `AuthorizeInterceptor.requireFourEyesOrProceed` takes its `no_approval_store` branch: it logs an
  error and **proceeds** (ADR-0155 D3 keeps it a no-op rather than failing closed). Flipping
  `AUTHZ_FOUR_EYES_ENFORCE` today would therefore gate nothing while appearing to gate two
  money-path actions, which is a worse state than being visibly off. Prerequisite: an
  `ApprovalStore` of the kind account-, balance- and billing-service carry
  (`infrastructure/approval/ApprovalConfig.kt`). One decision to take deliberately at that point:
  `delegation.reserve.release` is a routine customer spending action that matches the `release`
  verb by accident of naming, so inheriting the money-path default would put a second approver in
  front of ordinary customer traffic.

- **2026-09-01** — Lifecycle transitions gained database-authoritative monotonic revisions (T17). The migration is expand-compatible with an old producer for acyclic transitions; legacy reinstatement fails closed because only a revision-aware writer can distinguish a repeated SUSPENDED state. The database state machine supplies the revision and the deferred outbox trigger stamps the committed value. Rollout is consumer-first and quiesces writers while immutable V8–V10 apply; producer deployment before revision-aware account/card cursors would publish an ordering fact nobody enforces. Rollback retains the lifecycle migrations when reverting the producer image, because their triggers are the compatibility layer for that old image.

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or authorization bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-09** — customer-edge → delegation-service admitted at the network layer (#4248). The URL lived only in `openbank-customer-edge/src/main/resources/application.yaml` and the resource's `@ConfigProperty` default, so `gen-network-policies.py` — which derives every ingress allow-list from gitops `env:` — never saw the edge and delegation's policy never named customer-edge. Declaring `DELEGATION_SERVICE_URL` in customer-edge's Deployment env regenerates the policy to admit `customer-edge` on 8126. **No new caller and no new capability**: this is the same caller the API was always designed for, and every request still passes OIDC + `ROLE_API` + the OPA sidecar. What changes is that the hop now reaches those controls at all. Two consequences worth recording. (a) The plaintext hop is now visible to the ASVS V9.1 gate and baselined there with the same reasoning — the traffic was already plaintext, it was simply undeclared; it retires with the fleet-wide mTLS work. (b) It makes the OPA gap in #4196 observable: `delegation.reserve`, `.confirm` and `.release` are absent from `edge-service-delegation` in `delegation_rest_ext.rego`, so once traffic arrives they answer a clean 403 instead of timing out. Rollback: drop the env var and regenerate; the edge returns to being dropped.

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. Four parameters on the grantee-response endpoints: `granteePartyId` on accept/decline/renounce and `scaSessionId` on accept. Both are authorization-relevant — `granteePartyId` names WHO is responding to the grant and `scaSessionId` is the SCA evidence for accepting it — so a null reaching the use case is a delegation transition with no identified actor. The `X-Customer-Party-Id` header stays nullable by design (its absence is what distinguishes a bank-initiated call). No new caller or boundary. Rollback: revert.
- **2026-08-08** — Cumulative ceilings became a control (ADR-0249 D3). New inbound REST surface: reserve / confirm / release on `/delegations/{id}/reservations`, boundary 4 above, rows T14 and T15. `dailyLimit` / `monthlyLimit` stop being refused (#3613) because a place now exists where spend is observed *before* it happens. Two properties carry the row: concurrency is a `FOR UPDATE` row lock on the grant, not an in-JVM lock (which does nothing across replicas), and idempotency is a unique index on `(grant_id, idempotency_key)`, not a read-then-write. Windows are `AccountingClock.BANK_ZONE` per ADR-0207 D1 rather than a second hand-written `ZoneId.of("Europe/Prague")`. Residual, unchanged: no audit envelope on the new transitions (T4), no rail other than the edge's delegated-payment path asks the counter, and pre-#3613 grants still carry ceilings nobody counted. Rollback: revert — the reservation table is additive and no existing path reads it.
# Client draft preview

`POST /api/v1/delegations/preview` deliberately creates no authority. It repeats the caller,
constraint, resource-ownership and party-eligibility gates used by `offer`, but never reads or
consumes SCA, writes a grant or publishes an event. `offer` repeats every check so a stale preview
cannot become authorization. The response contains only `valid: true`: returning counterparty
attributes for an arbitrary UUID would turn the pre-SCA endpoint into a party-directory oracle.
