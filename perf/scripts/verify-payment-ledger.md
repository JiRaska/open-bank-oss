# Internal CZK transfer ledger oracle pilot

`verify-payment-ledger.py` is an offline financial consistency check for one isolated internal
CZK transfer pilot. It derives its result from intended instructions and read-only snapshots of
the transaction and ledger databases. It does not accept HTTP 201, k6 counters, a submitted
`passed` field, or an externally supplied verdict as financial evidence.

This pilot checks that every intended run-scoped key has exactly one completed `TRANSFER` row
with the intended amount, currency, source account and target account; that it has exactly one
`POSTED` journal; and that the journal has exactly two CZK lines: a debit for the source account
and a credit for the target account, both for the intended amount and both on the enabled CZK
customer-deposit-control GL account. Each line's `base_amount` and `base_currency` must also equal
the CZK instruction amount/currency. Every journal must have `synthetic=true`, from the
ledger-owned taint dimension added by V27. The GL mapping is pinned to code `2100` and stable ID
`a0000000-0000-0000-0000-000000000002` from
`openbank-ledger-service/src/main/resources/db/migration/V3__ledger_governance.sql`; the
synthetic journal column is from
`openbank-ledger-service/src/main/resources/db/migration/V27__synthetic_journal_dimension.sql`.
Reversal rows are explicitly unsupported and fail the pilot. The verifier checks journal
balances per currency in addition to the stricter transfer-leg checks.

## Schema-v2 causal closure for a shared sandbox

The additive `verify-payment-ledger-v2.py` and
[`payment-ledger-snapshot-v2.sql`](payment-ledger-snapshot-v2.sql) support a shared-sandbox
read-only snapshot without changing the schema-v1 isolated-pilot contract. V2 closes the causal
chain from every run-prefix idempotency claim through its transaction row, the derived
`workflow-<transaction-id>-ledger` idempotency key, the pointed journal header, and all journal
lines. It does not filter linked evidence by transaction or journal status or by a time window.
An optional `observed_transaction_id` on an expected instruction is retained and checked against
the persisted claim and row; do not remove rejected, timed-out, or otherwise unwanted intended
instructions from the expected artifact.

Capture the transaction snapshot first. For the ledger query, pass the union of transaction IDs
from **all** captured run-prefix claims and rows, plus every `observed_transaction_id` in the
expected artifact when present. This keeps a response ID visible even when its claim or row is
missing. V2 separately preserves the declared time-window census and whole-table orphan-line
census. Linked-run success with unrelated window entries or any orphan lines is `inconclusive`,
not an overall pass; a linked-run failure remains `fail`. A clear snapshot is still only
offline database consistency evidence: it does not attest the request source, prove workload
completeness, or qualify capacity.

Use the same snapshot and credential handling rules above, substituting the v2 SQL and verifier.
The transaction query returns both `claims` and `rows`; the ledger query requires their complete
ID union, not merely successful response IDs:

```sh
psql -X -qAt -v ON_ERROR_STOP=1 'service=capacity-transactions' \
  -v run_id="$RUN_ID" -v run_prefix="$RUN_PREFIX" \
  -f perf/scripts/payment-ledger-snapshot-v2.sql > /path/outside/repo/transactions-v2.json

TX_IDS=$(jq -r --slurpfile expected /path/outside/repo/expected-v2.json \
  '[.claims[].transaction_id, .rows[].id] + [$expected[0].instructions[] | .observed_transaction_id // empty] | unique | "{" + join(",") + "}"' \
  /path/outside/repo/transactions-v2.json)
psql -X -qAt -v ON_ERROR_STOP=1 'service=capacity-ledger' \
  -v ledger=true -v run_id="$RUN_ID" -v transaction_ids="$TX_IDS" \
  -v started_at="$RUN_STARTED_AT" -v ended_at="$RUN_ENDED_AT" \
  -f perf/scripts/payment-ledger-snapshot-v2.sql > /path/outside/repo/ledger-v2.json

python3 perf/scripts/verify-payment-ledger-v2.py \
  --expected /path/outside/repo/expected-v2.json \
  --transactions /path/outside/repo/transactions-v2.json \
  --ledger /path/outside/repo/ledger-v2.json \
  --output /path/outside/repo/oracle-v2-result.json
```

V2 is a consistency and attribution aid, not a qualification gate. Ambient records that cannot be
attributed to the run remain unresolved; do not relabel them as unrelated or suppress them to
obtain a pass. Snapshot permissions, ownership, request evidence and approved run boundaries must
be established independently.

## Inputs

The three JSON artifacts have `schema_version: 1`, the same `run_id`, `profile_id:
cz-current-account-5m-v1`, and integer `profile_version: 1`. The expected artifact also carries
`scope: internal-czk-transfer-pilot`, timezone-qualified `started_at`/`ended_at`, and a non-empty
`instructions` array. Each instruction has a unique `instruction_id`, the exact key
`capacity-pilot:<run_id>:<instruction_id>` (at most 100 characters), `type: TRANSFER`, positive
plain-decimal-string `amount`, `currency_code: CZK`, and distinct source/target UUIDs. The
transaction snapshot uses `source: transaction-service` and `rows`; the ledger snapshot uses
`source: ledger-service`, `journal_entries`, `journal_lines`, `gl_accounts`, and
`orphan_journal_lines`. Each ledger entry includes boolean `synthetic`; each line includes
`base_amount` as a decimal string and `base_currency`. Database numeric amounts must be exported
as text, not JSON numbers, to avoid binary floating-point conversion.

The snapshots must include all rows for the run key/window, including failed, pending, rejected,
or otherwise unwanted states. Do not filter rows to only successful instructions or journals.
Transaction rows are selected by the full run prefix. Ledger entries are selected from the
isolated ledger DB for the declared run time window; their lines and the read-only orphan-line
check are included. The workload stack must be isolated and have no unrelated writes in that
window, since otherwise unrelated journal entries intentionally fail the oracle.

## Read-only extraction contract

Use a local `psql` service definition or `PGPASSFILE`; do not put credentials in tracked files,
command arguments, or snapshots. Execute each query in its own
`BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY` transaction and save its single
JSON result verbatim outside the repository. Use the same immutable `RUN_ID`, run window and
profile values for each artifact. The script checks run/profile/version binding, capture time,
snapshot age (default and hard cap 24 hours), cross-database capture skew (default and hard cap
5 minutes), duplicate partition rows, and journal timestamps against the declared run window.
Stricter age/skew values are allowed and recorded in `snapshot_policy`; looser values fail.
It cannot make the two database snapshots atomic; capture them after the workload is drained and
the journey deadline has elapsed.

The single executable psql source is
[`payment-ledger-snapshot.sql`](payment-ledger-snapshot.sql). Omit `ledger` when connecting to
the transaction DB; set `ledger=true` for the ledger DB. Supply the same run ID to both branches,
and run-window timestamps plus all transaction IDs from the raw transaction snapshot to the
ledger branch. Example, with local `psql` service definitions and all outputs kept outside Git:

```sh
RUN_ID=pilot-2026-10-28-a
RUN_PREFIX="capacity-pilot:${RUN_ID}:"
psql -X -qAt -v ON_ERROR_STOP=1 'service=capacity-transactions' \
  -v run_id="$RUN_ID" -v run_prefix="$RUN_PREFIX" \
  -f perf/scripts/payment-ledger-snapshot.sql > /path/outside/repo/transactions.json

TX_IDS=$(jq -r '[.rows[].id] | "{" + join(",") + "}"' /path/outside/repo/transactions.json)
psql -X -qAt -v ON_ERROR_STOP=1 'service=capacity-ledger' \
  -v ledger=true -v run_id="$RUN_ID" -v transaction_ids="$TX_IDS" \
  -v started_at="$RUN_STARTED_AT" -v ended_at="$RUN_ENDED_AT" \
  -f perf/scripts/payment-ledger-snapshot.sql > /path/outside/repo/ledger.json
```

The SQL uses the run-window predicate without a `status` filter, plus every observed run
transaction ID. Thus failed/pending entries, late or duplicate effects, and unrelated in-window
journals remain visible. It exports all lines for selected entries and a whole-table orphan-line
anti-join. It intentionally fails if an unrelated journal appears in the isolated run window.

Run the verifier after saving the three raw files:

```sh
python3 perf/scripts/verify-payment-ledger.py \
  --expected /path/outside/repo/expected.json \
  --transactions /path/outside/repo/transactions.json \
  --ledger /path/outside/repo/ledger.json \
  --output /path/outside/repo/oracle-result.json
```

Exit `0` means the supported database invariants passed; exit `1` means the derived result failed;
invalid or stale artifacts also produce a machine-readable failed result and a non-zero exit.
The output hashes the three exact input bytes and reports derived row counts and failures. It is
**offline content-integrity evidence only**: it does not verify signatures, workflow identity,
trusted runner provenance, or that the supplied run metadata is truthful. It does not qualify the
5-million-client profile, capacity, read latency, balance projections/convergence, holds,
authorization, regional DR, or external-rail settlement. Those require separate executable
scenarios and independent evidence.
