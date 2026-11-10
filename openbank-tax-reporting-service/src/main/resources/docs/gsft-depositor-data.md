# GSFT depositor data — insured claims and the batch file — tax-reporting

When a payout event is declared, the bank must deliver depositor data to the Financial Market
Guarantee System (GSFT) within **3 working days of the relevant day** (zákon č. 21/1992 Sb.
§ 41d odst. 4), and GSFT tests that capability at least yearly (§ 41n). The format is the
annex to vyhláška č. 71/2011 Sb. This package (`com.openbank.tax.domain.gsft`) holds the two
pure domain pieces of that delivery. The domain performs no I/O and reads no clock; its inputs
are sourced by `GsftSnapshotPort` (see "Data sourcing"). No endpoint is wired yet (issue #15356).

## Insured claims (`InsuredClaims.assemble`)

For one relevant day, over a snapshot of depositors, deposit accounts and the ČNB rates of
that day:

- a depositor's eligible balance is principal plus accrued interest over every account they
  hold; a joint account splits by declared shares, or equally when none are declared — the
  account total is converted to CZK and rounded first, then split in haléře by largest
  remainder, so the shares of every account sum exactly to its total (DIA, DIL and DIC
  reconcile);
- foreign currency converts at that day's rate; a missing rate, or rates from another day,
  **fails** the assembly, because skipping an account would understate a claim silently;
- compensation is capped at the CZK equivalent of EUR 100 000;
- excluded depositors (§ 41b, with the reason) and synthetic depositors or accounts are dropped
  and **counted**, so the exclusion is evidenced rather than assumed;
- an overdrawn account is not a deposit: it is dropped before aggregation and counted, so it
  never nets against the depositor's other balances. Whether the statutory set-off of a
  depositor's liabilities against the claim should apply here is an unresolved legal question;
  this behaviour is provisional until it is settled.
- claims paid at the cover limit are counted (`cappedClaims`), and every exclusion count is
  carried into the rendered batch alongside the files.

## Batch renderer (`GsftBatchRenderer.render`)

Renders the assembly into the decree's files: `DIW` (header with counts, `T_PAYMENT` and the
`TEST`/`KEEN` marker), `DIC` (entitled persons), `DIA` (one row per owner and account) and
`DIL` (shares of joint accounts). Encoding is CP-1250 with CRLF and comma separators; amounts
are 15 digits of hundredths; a file with zero records is omitted. A person whose text fields
carry a control character (anything below 0x20, including CR and LF) or a character CP-1250
cannot encode, or whose IDC or NAME is blank, is rejected individually; a duplicated party or
account in the input fails the whole batch.

**One person's bad data never stops the batch.** A field CP-1250 cannot encode, an over-long
field, a missing identity or an amount that does not fit rejects that person only: they are
listed in `GsftBatch.rejected` with the reason, for correction and a follow-up tranche, and
`T_PAYMENT` counts only persons actually rendered. Only batch-level errors (bank code, batch
number, rate day) fail the render.

## Data sourcing

`GsftSnapshotPort.snapshot(D)` (adapter `infrastructure/returns/gsft/GsftSnapshotAdapter`) returns
the inputs of `assemble` and `render` for relevant day D. It uses only reads this service's own
machine identity is admitted to by the provider's policy, reusing the CRS/FATCA clients. A field
with no verified source is never filled with a plausible value: it is listed in
`GsftSnapshot.gaps`, and `requireComplete()` refuses any snapshot whose gap set is non-empty.
**Today every gap below is open, so no snapshot is fit for a KEEN delivery.**

Sourced:

| Input | Source |
|---|---|
| Account roster | account-service `GET /api/v1/accounts/active` (cursor-walked to the end). Only `accountType` CURRENT, SAVINGS and TERM_DEPOSIT are deposits; NOSTRO and GL_* are dropped; any other or missing type fails the snapshot. |
| IBAN, currency, holder party | the same roster row (`accountNumber`, `currencyCode`, `partyId`) |
| Principal | balance-service `GET /api/v1/balances/{accountId}/{currency}?asOf=D` (booked balance; 404 reads as zero, any other failure fails the snapshot) |
| Synthetic taint (ADR-0252) | this service's own CEÚ party classification (`CeuRegisterRepository.syntheticParties`), as known at snapshot time. An account is synthetic when its holder is. A party whose lifecycle events predate the projection, or carried no classification, reads as real (the fail-to-real direction ADR-0252 accepts). |

Unresolved gaps (each a `GsftDataGap`):

- **ROSTER_ACTIVE_ONLY_CURRENT — unresolved.** The roster is the CURRENT list of ACTIVE accounts.
  Dormant and frozen accounts hold insured deposits and are missing; an account closed after D is
  missing, one opened after D is present. Claims are understated.
- **JOINT_HOLDERS — unresolved.** account-service carries a single `partyId` per account, so every
  account is reported single-holder with no shares; DIL is never produced from sourced data.
- **ACCRUED_INTEREST — unresolved, understates every claim.** interest-service exposes accrual
  reads, but its policy admits no rule for this service's identity, so accrued interest is set to
  zero and flagged. Every claim is understated by the interest accrued up to D.
- **DEPOSITOR_IDENTITY — unresolved.** No party-service read exposes birth number, registration
  number or address to this caller. No `DepositorIdentity` is built, so the renderer rejects every
  person; nothing is fabricated from the holder name.
- **EXCLUSION_CATEGORY — unresolved.** No read exposes the § 41b exclusion category. `exclusion` is
  null meaning "not known", and the gap is flagged.
- **FX_RATES — unresolved.** fx-service serves ČNB fixings for a day through
  `GET /api/v1/fx/rates/{base}/{quote}/history?source=CNB&from=&to=` (the latest-only
  `GET /api/v1/fx/cnb/rates/{base}` cannot answer for day D), but its policy admits no rule for this
  service's identity. `rates` is null.

Closing a gap needs a provider-side read (or policy rule) in another service, which is out of
scope for the sourcing change; each closure must shrink `GsftSnapshotAdapter.UNRESOLVED_GAPS`
together with the source that closes it.

## Not yet here

Closing the data-sourcing gaps above, the institutional file (`DII`), ZIP/PGP
transport, four-eyes attestation and filing, the scheduled TEST dry run, and temporarily high
balances are follow-ups on #15356.
