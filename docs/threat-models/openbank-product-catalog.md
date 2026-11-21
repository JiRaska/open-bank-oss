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

## Residual risk / follow-ups
- **No subject-evidence writer exists yet.** `admit_passport_account` requires an unexpired row
  in `passport_grant_subject_evidence`, and nothing in this change writes one. In production,
  every new admission is therefore refused. That is fail-closed by design until the evidence
  publisher (C4) lands.
- **BRANCH mode is refused.** A `BRANCH` grant can be stored and approved, but admission returns no
  permit for it. Branch booking needs the separately approved branch ledger path.
- Consumers of the V18 grant lifecycle stream are not enabled yet. Until one is, the outbox rows
  wait unconsumed. They are not on the admission path, so they admit nothing.

Rollback: revert the account-service consumer first, then this service. The Flyway migrations are
forward-only once applied. Preserve approval history and consumed permits.
