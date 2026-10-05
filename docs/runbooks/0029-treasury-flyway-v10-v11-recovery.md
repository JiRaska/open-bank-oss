# Treasury Flyway V10/V11 recovery (#11561)

V10 was committed after V11. Keep both files, names, SQL, and checksums intact:
renumbering a committed migration changes its identity and fails the forward-only
database gate. The commit-order gate has a baseline for this known inversion;
that baseline does not establish the migration state of every deployment.

## Evidence and scope

On 2026-10-05, one accessible nonproduction Treasury installation had successful
V10, V11, V13, and V14 rows, in that installed order. Their checksums matched
the current migration files, the service was ready, and V12 had no history row.
Three available Kubernetes contexts led to that **same** installation. No other
Treasury database history was accessible in this audit. Do not extrapolate the
observed state to another environment or infer it from source commit order.

Before any rollout, privately inventory **every** target database with a
read-only query of `flyway_schema_history`: `installed_rank`, `version`,
`script`, `checksum`, and `success` for V10 onward. Compare checksums with the
exact source revision to deploy. Keep database identities, access details,
checksums, and row data out of public issues and PRs; record only a redacted
state count and the owner's sign-off.

## State-specific decision

| Observed history | Safe next action |
| --- | --- |
| Neither V10 nor V11 applied | A fresh install or a database ending at V9 applies V10, then V11, then V13/V14 in numeric order with normal Flyway settings. Prove this with a fresh boot against a representative history. |
| V10 successful, V11 absent | Keep V10's identity and checksum; allow normal ordered migration of V11 and later versions after verification. |
| V10 and V11 successful with matching checksums | No repair. Record installed order and verify the next boot and application readiness. This is the one observed sandbox state. |
| V11 successful, V10 absent | Stop routine rollout. Verify a recoverable database backup, the current schema and checksums, and V10's effect on existing nostro statements. An owner-reviewed, installation-specific **temporary** out-of-order setting may apply the unchanged V10 after V11; first prove the path on a restored representative database. Remove the setting only when every affected target records successful V10 with its original identity and checksum, then verify a normal boot with the setting off. |
| V10 or V11 failed, or an applied checksum differs | Stop. Preserve history and data, investigate the failed DDL or source mismatch with the database owner, and restore or design a reviewed forward repair. Do not rename, edit an applied file, delete a history row, or run `flyway repair` merely to make validation green. |
| V12 already applied | Stop and identify its source and checksum before any deployment; the current source has no Treasury V12. Do not reuse its number or assume it is the abandoned V10 rename. |

V12 remains unused in current source. The competing confirmation change from
[#11550](https://github.com/JiRaska/open-bank-oss/pull/11550) landed through
[#11555](https://github.com/JiRaska/open-bank-oss/pull/11555) as V13; V14
followed. A future migration must use the next available version after V14.

## Rollout and rollback boundary

Keep the normal GitOps, money-path review, attestation, and CI gates. Verify the
exact proposed head with both Flyway gates and fresh Treasury boot/migration
tests for each observed history. Roll out only after the private inventory is
complete. A temporary out-of-order setting must be limited to installations
that need it and removed after successful V10 application everywhere it was
needed; it must not become the fleet default.

Before deployment, retain a tested recovery point. If migration fails, stop
the rollout and restore that point or use a separately reviewed forward fix.
Reverting only the application image does not undo applied Flyway DDL or its
history, and dropping V10's column may discard data. Keep the original
migration identities in either recovery path.
