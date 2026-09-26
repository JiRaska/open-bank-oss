# SCA credential revocation

Use `DELETE /api/v1/sca/parties/{partyId}/devices/{deviceId}` to revoke a credential.
The device id comes from the party's device listing. Customers must own that party;
an unresolved customer is refused even if its token also has the API role. The existing
money-path four-eyes requirement applies when both authorization and four-eyes enforcement
are enabled. The latter is a separate opt-in; deployment must not be inferred from an OPA
allow decision. Follow [the SCA operator approval flow](sca-operator-approvals.md).

A 204 response means revocation, cancellation of that credential's unconsumed pending or
completed approvals, and the `DEVICE_REVOKED` outbox event committed together. Repeating
the request returns 204 without another event. An already consumed operation is not reversed.
If consumption races with revocation, the challenge row serializes the two: consumption
can win before revocation, but cannot use a cancelled challenge afterward.

The decision writer and revocation acquire the credential lock before a challenge lock.
Revocation increments cancelled challenges' optimistic versions so an earlier verifier
cannot overwrite cancellation. Keep this order when extending either path.

Public keys and signed decisions remain as evidence. Re-enrollment cannot reactivate a
revoked credential. Replacement uses a new credential and normal enrollment controls.
This endpoint does not implement identity recovery, revoke customer-edge WebAuthn
credentials, or invalidate unrelated login sessions.

## Rollout and rollback

Apply additive Flyway V13 and replace all SCA writers before enabling revocation traffic.
Old binaries do not check the marker and must not receive decisions after a revocation.
Retain both the marker and index on rollback. After any credential has been revoked,
restore only a binary that enforces revocation; an older one reauthorizes the keys.

For a lost response, query the device's `revokedAt` and outbox event before retrying.
A broker acknowledgement is not proof of audit ingestion: inspect the audit store separately.
If the outbox write fails, credential and challenge changes roll back too. Fix the cause
and retry; do not directly update the marker or delete retained evidence.

`ScaDeviceRevocationIT` drives real HTTP with PostgreSQL, including cancellation, ownership,
idempotency, concurrent decision submission and an injected audit failure.
`ScaLifecycleSafetyIT` checks that a stale verifier cannot undo cancellation.
`ScaFourEyesFlowIT` additionally runs enforced authorization over real HTTP with the generated
deployment OPA bundle, Redis and PostgreSQL. It exercises distinct maker/checker identities,
target substitution and replay. A live approval-flow drill remains necessary.
