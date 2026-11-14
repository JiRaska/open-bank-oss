# Four-eyes by person, not by account

`com.openbank.libs.governance.Proposal` is the fleet's maker-checker primitive. A
four-eyes control compares the two people who act on a decision. Comparing their
ACCOUNTS is not enough: one natural person with two staff accounts would satisfy an
account comparison on their own.

## The rule

- **Who acts is a `StaffIdentity`** (libs-domain): the account, the person id, and
  whether the sign-in used a second factor. `StaffIdentities.from(JsonWebToken)`
  (libs-runtime) reads it from the VERIFIED access token: `person_id` and `amr`.
- **`Proposal.propose`** records the proposer's person id and a mandatory expiry
  (at most seven days). No person id: the proposal cannot be made.
- **`approveBy` / `rejectBy`** compare PERSON ids and refuse:
  the proposer's person on any of their accounts; a missing person id on either side;
  a caller-supplied conflicted person; a confirmation without a second factor; an
  expired proposal.
- **`withdrawBy`**: only the proposing person may withdraw. A pending proposal is
  replaced only after it is withdrawn or has expired — never silently, and a proposer
  cannot stall a decision beyond the expiry.
- **No fallback.** The older account-only verbs refuse a person-bound proposal.
- **A proposal without a person id or an expiry** (rows written before this rule) is
  inert: it never blocks a new proposal and can never be confirmed.

## Where the identity comes from

The Keycloak realm declares a `person_id` user-profile attribute (admin-edit only) and
maps it, together with `amr`, into the access token. Every account of one person carries
the same `person_id` — the HR employee number. Keycloak cannot enforce that the value is
assigned correctly; that is covered by admin-only edit, admin events and the joiner/mover
review.

## Adopting it in a service

A call site becomes person-bound when it calls `propose` with a `StaffIdentity` and
stores the person id and expiry with the proposal. Call sites that still use the
account-only API behave exactly as before until they adopt it.
