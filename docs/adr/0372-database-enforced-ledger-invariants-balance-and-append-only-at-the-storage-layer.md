---
date: 2026-11-11
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, database, audit]
summary: "The ledger database itself refuses an unbalanced posted journal and any rewrite of a posted journal or its lines; a repair needs a transaction-scoped, logged override. The domain check stays, as the first of two."
---

# ADR-0372 — Database-enforced ledger invariants: balance and append-only at the storage layer

## Context

ADR-0025 decided that journals balance per currency. ADR-0039 made the ledger the golden
source of the booked balance. ADR-0133 made the audit trail tamper-evident. All three rest on
two properties of `journal_entries` and `journal_lines` that only application code enforces.

Measured on `origin/main` f0216a5686, over every file in the ledger migration directory:

- `JournalEntry` requires debits to equal credits per currency in the domain model. No
  constraint or trigger in the schema repeats it.
- Triggers that refuse a change exist only on derived tables (V23, V36, V37). The triggers on
  the journal tables invalidate history snapshots when a line is updated or deleted: the
  schema anticipates a rewrite and does not refuse it.
- V10 and V13 are repair migrations that updated `journal_id` on posted lines.
- `journal_entries.status` starts as `PENDING` and the row carries a `version` column, so a
  journal row is legitimately updated at least once during posting.

Any writer that bypasses the domain model (a migration, a repair script, a new repository
method, a manual session) can therefore leave an unbalanced or altered journal that the
database accepts. Tie-out and close evidence find it later. Issue #15718 tracks the gap.

## Decision

We will enforce both invariants in the ledger database, in addition to the domain model.

1. **Balance at commit.** A deferred constraint trigger checks, for every journal touched in
   the transaction whose status is posted, that debits equal credits per currency. A
   transaction that would commit an unbalanced posted journal fails.
2. **Append-only once posted.** A posted journal row accepts no update other than the
   explicitly listed non-financial transitions the posting lifecycle needs; the list is
   derived from the code paths that update the row today and written into the migration.
   Lines of a posted journal accept no update and no delete. Corrections are reversal
   journals, as the domain already requires.
3. **A repair is explicit and logged.** A migration or operator that must rewrite a posted
   row sets a transaction-local setting naming the reason. The trigger then allows the
   change and writes the reason, the statement kind and the row identity to a repair-audit
   table that is itself append-only. Without the setting the change fails.
4. **Proof by effect.** A real-database integration test bypasses the application and shows
   that an unbalanced posted journal, an update of a posted line and a delete of a posted
   line each fail, and that the override path succeeds and leaves an audit row.
5. **Measure before enforcing.** The trigger cost is measured on the bulk paths (day-end,
   interest capitalisation, year-end allocation) before the migration merges. Statement-level
   triggers with transition tables keep the check proportional to journals touched.

Existing rows are not rewritten. If the migration finds posted journals that are already
unbalanced, it reports them and the invariant is created `NOT VALID` for history only; the
count goes into the PR, and a non-zero count becomes its own issue.

## Alternatives considered

- **Application-level only (status quo).** No latency cost, one place to read the rule.
  Rejected: the rule binds only writers that choose to use the domain model, and the two
  repair migrations show such writers exist.
- **Privilege separation: the runtime role has INSERT only on journal tables.** A stronger
  boundary than a trigger, because it also stops code that sets the override. Not chosen as
  the first step: Flyway and the service share credentials today and the posting lifecycle
  updates the journal row, so it needs a role split through the dynamic-credential setup. It
  is the intended second step once point 2 has reduced updates to a known list.
- **A separate immutable store (event log or WORM) as the system of record.** Strongest
  guarantee. Rejected: replaces the ledger's storage model for a property two triggers give.

## Consequences

**Positive**
- An unbalanced or rewritten posted journal can no longer be committed silently, by anyone.
- Future repairs leave a record in the database instead of only in a migration file.

**Negative**
- Every posting transaction pays for a deferred check. The cost is unknown until measured.
- The history-invalidation triggers on the journal tables become reachable only through the
  override path; they stay until that path is exercised by a test.
- A session-level setting is a guard against mistakes and unreviewed code. It does not stop
  a database superuser; tamper evidence for that case remains the closed-period anchors.

**Neutral**
- Planned gate `ledger-journal-rewrite-declared`: a ledger migration that updates or deletes
  from a journal table must set the override in the same file. Enforced with the migration.

### Delivery check

Not implemented in this revision:

```bash
grep -lE 'CONSTRAINT TRIGGER' openbank-ledger-service/src/main/resources/db/migration/*.sql
./gradlew :openbank-ledger-service:test --tests '*LedgerStorageInvariantIT'
grep -n 'id: ledger-journal-rewrite-declared' -A4 .github/gates/gates.yaml | grep 'mode: enforced'
```

The IT must report at least four executed cases, three of them refusals raised by the
database with the domain check bypassed.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is stored in the journal tables.
- DORA: not applicable — no ICT third party, recovery target or incident process changes.
- GDPR: not applicable — no personal data is added; the repair-audit table records a reason
  and row identifiers.
- PSD2: not applicable — no payment flow or authentication step changes.
- CNB: supports the integrity of accounting records that the statutory close (ADR-0096)
  attests; it is a technical control, not an attestation.

## References

- ADR-0025, ADR-0039, ADR-0096, ADR-0133
- Issue #15718
