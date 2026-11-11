# Domestic co-signature submission receipts

Customer Edge can attach `submissionCorrelation` to a held domestic payment. It computes the
domain-separated SHA-256 correlation from the original submission key and the authenticated
human, active entity, debit account and delegation authority. Delegation verifies the Edge
workload identity. It stores the correlation as a receipt outside the frozen payment.

The receipt, original approval, first signature and outbox events commit in one PostgreSQL
transaction. Concurrent submissions with the same correlation and frozen payload resolve to
the same approval. A changed canonical payload returns `409 SUBMISSION_CORRELATION_REUSED`.
The existing single-use SCA constraint applies across correlated and legacy submissions.
Approvals created without a correlation keep their existing SCA replay behavior and have no
submission receipt.

Verified Edge can POST to `/api/v1/entities/{entityId}/approval-requests/submissions/lookup`
with `initiatorPartyId` and `submissionCorrelation` after re-establishing the caller's scope.
The response supplies the approval ID, persisted lifecycle and any recorded successful rail
reference. A RELEASED status without that reference means the release has been claimed and its
rail result is still unknown. A missing receipt returns 404 and leaves execution unknown.
Lookup responses use `Cache-Control: no-store`.

Flyway V33 adds the append-only receipt table. Deploy the migration before the writer. To roll
back, stop sending and querying correlations and roll back the application. Retain the table
and its receipt evidence.

## Bind the route before authentication or dispatch

The verified Edge can claim the route for a business domestic submission before SCA and before
calling either the rail or the approval writer. `submissionKeyDigest` is the domain-separated
SHA-256 of the original idempotency key alone. PostgreSQL retains its association with the
initiator, entity, debit account, grant-bound correlation, canonical payload fingerprint and
DIRECT or HELD mode. The same original key with a different scope or payload returns 409.
The database chooses one binding when requests race. Currency comparison accepts the domestic
rail's case and whitespace normalization. The frozen payload retains the original spelling.

The Edge revalidates debit authority before calling Delegation. With policy enforcement enabled,
a DIRECT retry requires the current policy to allow one signature. A HELD retry without a
committed approval requires the current policy to require co-signature. The approval writer
rechecks this threshold before accepting the first signature. With enforcement disabled, a new
key can bind DIRECT and an existing HELD binding refuses dispatch. An existing committed
approval remains available through the read API even when policy changes.

Mode-bound approval creation supplies the original-key digest and the authority correlation.
Mode-bound receipt reads also supply the debit account. Existing receipts keep their original
lookup scope. A mode binding alone has no payment outcome. A direct payment outcome comes from
the rail's own receipt. The Edge must use the mode claim before either write path whenever it
uses the mode-aware contract. Both callers and provider must support the contract before it is
activated.

Flyway V34 adds the immutable mode binding and links new receipts to a HELD binding. PostgreSQL
records the first claim time. Claim age supplies no outcome evidence. Any archival or deletion
requires an explicit retention process that preserves protection against key reuse. Application
rollback retains the binding and receipt evidence.
