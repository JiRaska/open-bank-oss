# SCA operator approvals

SCA exposes a maker/checker queue to operators and administrators:

- `GET /api/v1/sca/approvals?limit=` — pending, unexpired approvals, oldest first (`limit` clamped
  to 1..200).
- `GET /api/v1/sca/approvals/{id}` — one approval in any status until its authorization deadline.
  A missing record does not prove the associated operation never happened.
- `PATCH /api/v1/sca/approvals/{id}` with `{"approve": true}` or `{"approve": false}`.

The checker is the authenticated caller (`principal.name`, the same representation the
interceptor records as the maker); the body never names the checker. The maker cannot decide
their own approval, and an approval can be decided once.

When `authz.enforce` and `authz.four-eyes.enforce` are both enabled and OPA flags the action
`four_eyes_required` (today `device.enroll`, `device.revoke` and `scaChallenge.consume`, minus the
identity-specific service-account exemptions in `rules.yaml: four_eyes.exemptions`), the original
call returns 202 with `approvalId` before any business write. After a different operator approves,
the maker retries the identical request with `X-Approval-Id`. The approval is bound to a SHA-256
fingerprint of that request's endpoint and arguments (the shared #11675 binding), so a different
device id, party, credential, public key or algorithm cannot borrow it; such a retry, a reused
approval or a different maker creates a fresh pending approval instead. The OPA resource id stays
the party id; no policy change is involved. The service has no blanket exemption for operators.

## Admin review

The admin approval inbox lists this queue (source `sca`) and links each item to
`/approvals/sca/{id}`, open to operators and administrators only, as the service is. The page shows
the action, the maker and the bound party (or challenge); the exact device of an enrollment or
revocation is bound by the request fingerprint, which the service does not publish, so confirm it
with the maker. Approval needs an explicit review tick and a separate confirmation. The maker is
not offered a decision on their own request (the service refuses it anyway). If a response is
lost, the page refuses another decision until the state is reloaded; it never retries the
business operation.

## Deployment

`AUTHZ_FOUR_EYES_ENFORCE` defaults to false and this change does not set it in any manifest.
Before enabling it, run an enforced maker/checker drill with the real identity provider and
database permissions, covering both the exempt service-account ceremonies and refused human
requests. See [the shared upgrade precautions](atomic-four-eyes-approvals.md).

## Durable evidence

SCA does not use the fleet's Redis approval store. Each transition (create, decide, claim) commits
in one PostgreSQL transaction with an `SCA_OPERATOR_APPROVAL_CHANGED` outbox event naming the actor
(maker on create and claim, checker on decide). A failed audit insert rolls the transition back.
Decision and claim do not extend the original deadline. Expiry hides a record from the approval
APIs; the row and its events remain until retention (below) deletes the row.

`EXECUTED` means the one-use authorization was claimed, not that the protected operation
committed. If a response is lost, inspect the device or challenge and its own outbox event before
asking for a fresh approval. Never edit stored approval state to replay an authorization.

## Retention

Approvals are authorisation evidence for the operations they gated, kept like device-decision
evidence ([sca-durable-decisions](sca-durable-decisions.md)): the daily
`OperatorApprovalPurgeScheduler` (cron `openbank.sca.approval-purge.cron`, default `0 45 3 * * ?`)
deletes rows whose authorization expired more than `openbank.sca.approval-retention-days`
(default 1826, AMLD Art. 40) ago, oldest first, in batches of `openbank.sca.approval-purge.batch-size`
(500) up to `max-batches-per-run` (100) per run. Every status qualifies once expired: an expired
PENDING approval can never be decided or claimed. A still-live approval is never deleted. The outbox
events are not touched.

- Health: `openbank_workflow_last_success_age_seconds{workflow="sca-operator-approval-purge"}` and
  the counter `openbank_sca_operator_approval_purged_total`. Neither exists when the purge is
  disabled — an absent series means "not meant to run", not "healthy".
- Legal hold or investigation: set `openbank.sca.approval-purge.enabled=false` BEFORE the next
  03:45 run, and re-enable when the hold lifts.

## Cutover and rollback

Because `AUTHZ_FOUR_EYES_ENFORCE` is off everywhere, no live approval exists when V15 lands. If it
was enabled before this change in some environment, pause governed SCA operator mutations and let
live approvals expire before deploying: an approval held in another store has no PostgreSQL row and
never authorizes a request here. For rollback, pause and drain live approvals first and keep the
`sca_operator_approvals` table and outbox rows — they are evidence. Never restore an old Redis
snapshot to resurrect a consumed authorization.

## Tests

- `ScaFourEyesFlowIT` — real HTTP, PostgreSQL and the generated deployment OPA bundle in the
  deployment's OPA image: parking, self-approval refusal, exact-request binding, single use, the
  queue, error mapping, customer denial and the existing service-account exemptions.
- `ScaOperatorApprovalDurabilityIT` — retained expiry evidence, concurrent checker/claim races,
  the per-maker pending bound under concurrency, binding round-trip and rollback on audit failure.
- `OperatorApprovalRetentionTest` and `OperatorApprovalPurgeSchedulerIT` — cutoff, batching and
  the per-run cap; the real cron deletes decided, claimed and expired-pending rows past retention and
  keeps rows inside retention and a live PENDING approval.
- `ScaOidcApprovalIT` — an isolated Keycloak realm from the deployment image's pinned base, real
  operator and service-account tokens (no `@TestSecurity`), durable audit actors, tampered or
  missing token rejection, and a full enrol → sign → consume → revoke lifecycle.

None of these prove production identity wiring or a live rollout.
