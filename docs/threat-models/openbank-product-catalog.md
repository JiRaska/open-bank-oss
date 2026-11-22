# Threat model — Product Catalog: passport grants and account admission (ADR-0380, ADR-0030)

**Surface:** `openbank-product-catalog` passport-grant administration (`PassportGrantResource`:
propose, approve, revoke) and the internal account-admission endpoint
(`PassportAccountAdmissionResource`: admit, receipt), with the V17 permit fence and the V18 grant
lifecycle publication.
**Posture:** the catalogue holds the authoritative grant row. Every admission decision is made
against that row inside one PostgreSQL transaction, never against a replica.

## Assets
- The passport grant: an approved, unrevoked authority to offer one offering in one EU host
  country, mode, channel and customer segment, inside an effective window.
- Account permits: single-use admissions bound to an operation (`OPEN` / `ACTIVATE`), a party,
  a product revision and a request binding digest.
- The approval record (proposer, approver, reasons) as evidence of four-eyes control.
- Staff identity, held as a minimised identifier rather than a name or e-mail.

## Trust boundaries
1. Staff browser → admin-ui → catalogue (grant administration). Human, MFA-authenticated.
2. account-service → catalogue (admission). Machine, client-credentials JWT.
3. Catalogue → `catalog_outbox` → downstream readers (grant lifecycle stream, V18).

## Threats & mitigations (STRIDE-ish)

| # | Threat | Mitigation | Evidence |
|---|---|---|---|
| S1 | A machine client or non-staff token administers grants | `requireStaff` admits only a token whose `azp` is `openbank-admin-ui`, that carries no `ROLE_API`, reports strong authentication (MFA) via `StaffIdentities`, carries a stable person identity, and holds the required catalogue role in scope via `CatalogScopeRoleMapper`. Anything else is `CatalogForbiddenException` (403). | `PassportGrantResource` |
| E1 | One person both proposes and approves a grant | Four-eyes is enforced twice. The table constraint `approved_by <> proposed_by` rejects the row, and `approve_passport_grant` only updates `WHERE proposed_by <> p_actor`. | `PassportPermitFenceTest` (`only a distinct staff person can activate a proposal for issuance`) |
| S2 | A caller other than account-service obtains an account admission | `requireAccountClient` requires `azp` = `openbank-account` and `preferred_username` = `service-account-openbank-account`, in addition to `ROLE_API`. Any other client is refused with 403. | `PassportAccountAdmissionResource` |
| T1 | An admission races a revocation and is issued against a revoked grant | `admit_passport_account`, `consume_passport_account_permit` and `revoke_passport_grant` all lock the same `passport_grants` row `FOR UPDATE`, so the two operations are serialised. Whichever takes the lock first wins; a revoked grant issues no new permit. | `PassportPermitFenceTest` (`revocation wins when it owns the grant row first`, `consumption wins and only the exact operation replays after revocation`) |
| T2 | A permit is reused for a different operation, party or revision | Permits are unique on `(operation, operation_id)` and are consumed once (`consumed_at`). A replay must match the stored operation, party, revision and `binding_digest`; a mismatch is refused. | `PassportPermitFenceTest` |
| R1 | An exact retry after revocation is mistaken for a new admission | The only post-revocation path returns the original immutable receipt for an already consumed permit. It never issues a new permit. A receipt lookup cannot consume an issued permit. | `PassportPermitFenceTest` (`receipt lookup cannot consume an issued permit and remains readable after revocation`) |
| I1 | Staff personal data spreads into grant rows and the lifecycle stream | Grants store a name-based UUID (`UUID.nameUUIDFromBytes` over the person identity), never a name or e-mail. The V18 snapshot carries no staff identifiers. | `PassportGrantResource` |
| T3 | A downstream reader acts on a rolled-back or stale grant | V18 writes each lifecycle change to `catalog_outbox` in the same transaction as the grant change. Readers only ever see committed states. Admission never consults a reader. | `PassportPermitFenceTest` (`independent catalog readers converge across restart and replay without observing a rolled back grant`) |

## Loan commitments on FOS routes (ADR-0380 C3)

`PassportCommitmentResource` (`/api/v2/internal/passport-commitments`: admit, receipt, route) extends
the same permit ledger to loan signing (`SIGN`) and disbursement (`DISBURSE`).

| # | Threat | Mitigation | Evidence |
|---|---|---|---|
| S3 | Another machine client, including account-service, signs or disburses a loan | `requireLendingClient` requires `azp` = `openbank-lending` and `preferred_username` = `service-account-openbank-lending`, in addition to `ROLE_API`. Any other client is refused with 403. | `PassportPermitFenceTest` (`account principal cannot admit a loan or read its receipt`) |
| T4 | A commitment is admitted on a route or product other than the one granted | `admit_passport_commitment` locks the grant row (`FOR UPDATE OF g`) and requires the bank, establishment, host country, channel, customer segment, pack-set digest, revision, revision content hash and configured loan `schema_id` to match before delegating to the shared admission. The shared admission still checks approval, revocation, the effective window, live subject evidence and the permit binding. | `PassportPermitFenceTest` (`only exact consumed loan operations return receipts`) |
| T5 | A revocation and a loan admission race | Both serialise on the same grant row. A revocation that wins prevents the admission; an admission that wins keeps only its exact receipt. | `PassportPermitFenceTest` (`revocation that owns the grant prevents a new loan admission`, `loan admission that owns the grant preserves only its exact receipt`) |
| T6 | Signing and disbursement share one permit, or an account operation reaches the loan path | `SIGN` and `DISBURSE` consume separate permits keyed by their own operation IDs. `admit_passport_account` now admits only `OPEN`/`ACTIVATE`, and `admit_passport_commitment` only `SIGN`/`DISBURSE`. | `PassportPermitFenceTest` |
| I2 | The route read leaks grants or admits something | The route read returns route fields only for an approved, unrevoked FOS grant with live subject evidence for that party and revision. It neither issues nor consumes a permit, and the receipt read never consumes one. | `PassportPermitFenceTest` (`only an approved live route for the exact verified subject and revision is returned`) |
| T7 | A refused loan commitment is later admitted for the same command, for example after new subject evidence arrives, so the lending side's terminal decision and the catalogue disagree | V20 retains each exact refusal in `passport_commitment_refusals` under the grant lock. `admit_passport_commitment` refuses any later attempt with the same operation, operation ID, grant, party, revision and binding. The response carries `X-FOS-Decision: refused` only for a retained refusal, so lending can tell a definitive refusal from a transient one. A route mismatch is not retained and stays retryable. | `PassportPermitFenceTest` (`an exact refusal does not become an admission when evidence arrives later`) |
| T8 | A FOS receipt or permit authorizes a BRANCH commitment, or the reverse | V21 passes the requested mode into `admit_passport_scoped_operation` and `admit_passport_commitment`, and refuses unless it equals the locked grant's `mode`. Establishment and route are matched under the same lock, so a FOS receipt cannot be replayed as a BRANCH one. | `PassportPermitFenceTest` (`revocation that owns the grant prevents a new loan admission`, `loan admission that owns the grant preserves only its exact receipt`, `only exact consumed loan operations return receipts`; each runs for FOS and BRANCH) |

## Account booking context on the admission receipt (ADR-0380 B2)

V22 makes the consumed account admission receipt the only source of the booking context that
account-service freezes (`/api/v2/internal/passport-account-admissions/context`).

| # | Threat | Mitigation | Evidence |
|---|---|---|---|
| T9 | A caller chooses or alters the bank, establishment, host country or mode an account is booked under | The context is read only from the exact consumed permit, matched on grant, operation, operation ID, party, revision and binding, under the named account-service identity. A request with any other binding, or before consumption, answers 404. Callers supply only the grant reference. | `PassportPermitFenceTest` (`receipt lookup cannot consume an issued permit and remains readable after revocation`; runs for FOS and BRANCH) |
| T10 | A grant's booking scope is edited after a receipt was issued, so a later read reports a different context | `trg_preserve_passport_booking_scope` rejects any update to bank, establishment, host country, mode, offering, channel, segment or pack-set digest on `passport_grants` (SQLSTATE 23514). | `PassportPermitFenceTest` (same test: the `UPDATE passport_grants SET bank_id` is refused) |
| T11 | An account is admitted in a host country the offering is not marketed in | The redefined `admit_passport_account` also requires the grant's host country in the offering's `market.countries` before it issues or consumes a permit. | Fixture seeding in `PassportPermitFenceTest` (admissions succeed only with a matching market country) |
| R2 | A receipt context disappears after revocation, leaving recovery unable to prove its booking | The context read, like the receipt read, stays available for an already-consumed permit after revocation and never consumes one. | `PassportPermitFenceTest` (same test, read after `revoke`) |

## Residual risk / follow-ups
- **No subject-evidence writer exists yet.** `admit_passport_account` requires an unexpired row
  in `passport_grant_subject_evidence`, and nothing in this change writes one. In production,
  every new admission is therefore refused. That is fail-closed by design until the evidence
  publisher (C4) lands.
- **BRANCH account operations are refused.** `admit_passport_scoped_operation` refuses `OPEN` and
  `ACTIVATE` in BRANCH mode. Account booking on a branch needs the separately approved branch
  ledger path (B2).
- **Nothing writes subject evidence yet.** Lending calls the commitment endpoint (#16539, #16587).
  It still admits nothing without a subject-evidence row, which nothing writes yet.
- **BRANCH loan commitments are admitted by the catalogue but not executed.** Lending refuses branch
  origination and commitment execution at normal runtime until B2 provides branch-aware booking.
- **No negative test for the V22 market-country denial yet.** The check is exercised only on its
  passing path; a dedicated mismatch case is a follow-up.
- Consumers of the V18 grant lifecycle stream are not enabled yet. Until one is, the outbox rows
  wait unconsumed. They are not on the admission path, so they admit nothing.

Rollback: revert the account-service consumer first, then this service. The Flyway migrations are
forward-only once applied. Preserve approval history and consumed permits.
