# Context read-audit commitment reconciliation

This is an operator check of the local Context read-audit outbox against the fleet Audit
hash chain. It compares only random audit IDs and SHA-256 commitments. It does not read
investigator identities, customers, cases, graph roots or evidence references.

Before exporting, verify the Audit chain and signed anchors using
[the independent anchor procedure](0014-independent-audit-anchor-verification.md). A
matching CSV pair alone does not prove that the fleet chain is intact. Use read-only,
approved database access, the same UTC cutoff for both queries, and restricted temporary
files. Do not place exports in the repository or normal application logs.

Export the entire retained history through the selected cutoff, ordered by `audit_id`.
For Context, export **every bank scope** separately; set the authorized
`openbank.bank_scope` transaction-local setting before each query so row-level
security applies. If one scope is omitted, reconciliation is incomplete even if its
absence happens not to create a mismatch. Project exactly these two columns to CSV with
the header `audit_id,commitment`:

```sql
SELECT audit_id::text AS audit_id, btrim(commitment) AS commitment
FROM context_audit_commitment_outbox
WHERE occurred_at <= :cutoff_utc
ORDER BY audit_id;
```

From Audit, project the corresponding centrally persisted commitments with the same
cutoff and header. The event's `occurred_at` comes from the Context outbox row:

```sql
SELECT entry_id::text AS audit_id, payload::jsonb ->> 'commitment' AS commitment
FROM audit_entries
WHERE event_type = 'CONTEXT_READ_AUDIT_COMMITTED'
  AND aggregate_type = 'CONTEXT_READ_AUDIT'
  AND source_service = 'context-service'
  AND aggregate_id = entry_id::text
  AND occurred_at <= :cutoff_utc
ORDER BY entry_id;
```

After both exports are complete, compare them locally:

```sh
python3 openbank-infra/scripts/reconcile-context-audit.py \
  --context /restricted/context-bank-a.csv \
  --context /restricted/context-bank-b.csv \
  --fleet /restricted/fleet-commitments.csv
```

Repeat `--context` for every bank scope. The program merges the individually sorted
streams with memory proportional to the number of scopes, not the number of records.
Exit code `0` means every exported ID and digest matches. Code `1` reports a missing
or different commitment; code `2` means malformed, unsorted, duplicate or empty input.
The JSON result includes totals and at most ten random audit IDs for triage. Any
difference is an incident finding: inspect the Context outbox status, Kafka lag and
dedicated DLQ, then the Audit chain. Re-export with the same cutoff after recovering
delivery. Never synthesize a matching row, change a digest or delete an outbox record.

This check is deliberately independent of the Kafka producer's `SENT` flag, which
establishes broker acceptance rather than fleet persistence. It is not yet a scheduled
production control; release of real-data investigative lenses also needs a measured
reconciliation interval, alerting, bounded lag and a successful sandbox run.
