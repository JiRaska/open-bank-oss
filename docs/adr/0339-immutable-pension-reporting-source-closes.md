---
date: 2026-10-13
decision-status: proposed
delivery-status: planned
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [regulatory-reporting, accounting-close, database, audit]
summary: "Seal aggregate pension reporting source revisions explicitly and retain their lineage alongside immutable statutory return revisions."
followup: "#12425 — provider close storage, tax lineage, cross-source reconciliation and hosted evidence remain incomplete"
---

# ADR-0339 — Immutable pension reporting source closes

## Context

ADR-0336 assembles a statutory return from provider aggregates and stores each return revision's
figures and content hash. Those stored figures reproduce what was submitted. They do not identify
which provider revision supplied each cell: `ReturnDataPort.fetch` returns only datapoint values.
The fund endpoint supplies a period-end NAV id, basis NAV ids and a fingerprint, but recomputes on
every GET. Publishing a NAV correction supersedes the old NAV and reprices transactions, so a prior
fingerprint cannot reconstruct its earlier source from today's mutable register. Participant
aggregates do not yet expose a close revision. ADR-0337's company ledger already requires frozen
period evidence; its read is scoped to the pension company's separate ledger deployment.

## Decision

1. **Provider-owned, explicit close.** Each source owner seals one immutable aggregate revision for
   a legal-entity scope and reporting period through an authenticated command after the period has
   ended. A read never creates a close. The fund provider captures its existing calculated aggregate
   response, closing and basis NAV ids, source fingerprint, creator and creation time under a close
   UUID. It stores the exact response bytes, explicit payload schema version and a SHA-256 digest.
   The schema version is the frozen DTO contract version, distinct from the close revision ordinal;
   historical reads return it rather than inferring it from the current live API. A later correction creates a new
   close UUID and revision; it never changes an earlier close. Repeating a seal for the same scoped
   period, fingerprint and schema version returns the same close only when the serialized payload and closing NAV
   id also match; a mismatch is a conflict. A separate immutable successful command receipt binds
   the actor, hashed operation-scoped `Idempotency-Key` identity and canonical request digest to
   that close atomically. Caller-supplied raw keys are not retained.
   Retrying the same command after a source correction returns its original close before reading
   the current source; a changed request with the same key is a conflict. A new key can seal the
   corrected source. Failed commands leave no receipt. The database rejects UPDATE/DELETE to a sealed close or receipt.
   The fund calculator is reused, not forked.
2. **Historical read and verification.** A close-by-id GET returns the stored aggregate response and
   metadata, verifies its digest before serving, and refuses an id outside the requested fund. The
   existing live `period-figures` GET remains for compatibility and is marked live/unsealed. Filers
   must use the close-by-id read once a closed-source contract is available; missing or mixed-version
   source evidence is unavailable, never silently replaced with a live GET.
3. **Tax lineage.** Extend the extraction result with typed source receipts per datapoint group:
   provider identity, legal-entity scope, period, close UUID or attested ledger-period id, provider
   revision/digest and mapping version. Persist these receipts with each immutable statutory-return
   revision and include their canonical digest in its attested content hash. Legacy revisions retain
   their old hash algorithm and are explicitly marked as having unavailable lineage; they are never
   backfilled from current providers. Approval and submission refuse incomplete receipts for new
   catalogue revisions whose datapoints require closed sources.
4. **Entity and cell separation.** Fund NAV/units, participant lifecycle, pension-company books and
   company investments remain separate receipts. Company ledger reads only its frozen trial
   balances from the separate legal-entity instance (ADR-0337). No service computes another
   owner's accounting fact. The return mapping records which source receipts supplied each cell.
5. **Retention.** The stored fund aggregates contain no participant-level attributes. Source close
   and statutory-return evidence follow the existing ten-year service retention policies in each
   `governance.yaml`; changing retention or adding participant-level data requires a separate
   reviewed decision. Backups and restoration must preserve source closes and filing receipts
   together; backup age is not the retention policy.

## Compatibility and rollout

- **Expand:** Add nullable source-close and receipt storage, APIs and client DTOs. Existing live
  reads and legacy return hashes remain valid. New writers can seal closes while old consumers still
  use the live endpoint. No historical rows are rewritten.
- **Contract:** Deploy provider close persistence and historical read, then participant and company
  source contracts, then tax extraction/lineage. Turn on closed-source assembly only after every
  required provider serves a compatible revision. Unknown or missing revisions fail closed. Dual-read
  must compare new closed figures with current mapped figures and independent control totals before
  retiring live extraction.
- **Forward repair:** A source correction creates a new provider close and a new statutory-return
  revision. An already approved/submitted revision and its source receipt remain unchanged.
- **Rollback:** Old readers continue using the legacy live endpoint during expansion. Roll back
  application behavior before dropping new storage. Once a return cites a provider close, preserve
  its close and command receipt rows and schema through any rollback; never downgrade by deleting
  attested evidence. A reviewed expiry process must eventually remove both together once the
  applicable retention obligation ends; no automatic deletion is introduced by this foundation.
- **Retry:** A repeated seal for identical scope, period and source fingerprint yields the same
  close. Concurrent seals have one winner; the other reads the winner. A changed fingerprint is a
  distinct revision. A failed insert leaves no partial close.

## Alternatives considered

- **Hash the current GET only:** detects change but cannot replay its earlier inputs after a NAV
  correction. Rejected.
- **Put source ids in numeric datapoints:** breaks catalogue validation and obscures cell types.
  Rejected.
- **Write a snapshot on GET:** a read would mutate regulatory evidence and concurrent reads could
  create accidental closes. Rejected.
- **Duplicate the fund/ledger calculators in tax-reporting:** creates two definitions of each
  accounting fact. Rejected.

## Consequences

Provider storage grows by one aggregate response per explicit revision. The fund foundation can be
shipped independently, but does not make #12425 complete: participant close evidence, separate
company books, cross-source reconciliation, tax lineage, hosted contract checks, money-path review
and deployed restoration evidence remain required. No generated wire format or regulator
acknowledgement is implied by a sealed source or assembled return.

A sealed fund response proves exactly which aggregate bytes tax could consume. It does not prove
that the old raw transaction rows can be recomputed independently after a correction, because
those rows are repriced in place today. Cell-to-raw-book reconciliation and preserved correction
inputs remain separate acceptance work. The existing ten-year governance declaration has no
close-specific purge scheduler yet; until one is reviewed, evidence is retained rather than
silently deleted, and restore tests must cover it.

## Compliance impact

The fund close stores a dated, digest-checked aggregate and its source identity for later
reproduction of a statutory return's source figures. It does not assert that the return itself
has been approved or filed. Human review, provider receipt mapping, reconciliation with the
independent books and restoration proof remain mandatory before a regulator-facing submission.
The aggregate excludes participant-level attributes; any expansion of its data scope requires
separate privacy and retention review.

## References

- ADR-0336, ADR-0337, ADR-0118, issue #12425 and final gate #12479.
- `openbank-pension-fund-service/governance.yaml`, `openbank-tax-reporting-service/governance.yaml`.
