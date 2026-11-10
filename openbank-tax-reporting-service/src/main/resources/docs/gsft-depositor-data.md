# GSFT depositor data — insured claims and the batch file — tax-reporting

When a payout event is declared, the bank must deliver depositor data to the Financial Market
Guarantee System (GSFT) within **3 working days of the relevant day** (zákon č. 21/1992 Sb.
§ 41d odst. 4), and GSFT tests that capability at least yearly (§ 41n). The format is the
annex to vyhláška č. 71/2011 Sb. This package (`com.openbank.tax.domain.gsft`) holds the two
pure domain pieces of that delivery. It performs no I/O, reads no clock and wires no endpoint
yet (issue #15356).

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
- compensation is capped at the CZK equivalent of EUR 100 000, except special-regime balances
  of their owner (see `DII` below);
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

## Special-regime accounts (`DII`)

An account in the special regime (účet ve speciálním režimu, § 41f) is held by an owner for
beneficiaries (podílníci). Verified against the annex (1.2.2, 1.2.5):

- the account is in `DIA` under its owner; a beneficiary is never in `DIA`;
- `DII` carries one record per beneficiary share: `D`, IDC, TYPE, ORDER_P, ACCOUNT, CURRENCY,
  AMOUNT, AMOUNT_CZK, sorted by IDC, TYPE, ORDER_P, ACCOUNT; `DIW.N_SPECIAL` counts them and a
  zero count means no `DII` file;
- the cover limit does not apply to the owner's `PAYMENT`, so special-regime balances are added
  uncapped on top of the owner's capped ordinary claim;
- a person whose funds are only on special-regime accounts has `PAYMENT` 0.

Shares are in hundredths; their CZK column is split from the owner's `DIA` CZK total by largest
remainder, so `DII` never sums past `DIA`. A beneficiary with bad data is rejected alone, like an
owner. A share on an account that is not in `DIA` (owner excluded, synthetic, overdrawn or
rejected) fails the batch: reporting beneficiaries of an account GSFT does not see would be
inconsistent, and how to treat them is not stated by the annex.

**Unverified / unresolved — the batch FAILS rather than guess:**

- the coefficient *k* (1.2.5.1), applied when a beneficiary holds funds through two or more
  owners, is not computed;
- whether a beneficiary who is also a depositor in their own right gets their `DII` shares merged
  into one `DIC` record is not stated;
- a beneficiary-only `DIC.T_CLAIM` is the sum of their `DII` shares in CZK — the annex says only
  "sum of all accounts under I_IDC" (*unverified*).

No owning service models special-regime accounts or their beneficiaries today, so nothing feeds
`SpecialRegimeShare` yet; the format is ready for when one does.

## Transport (`GsftPackage`)

Annex 1.1.6, verified: the batch is compressed with ZIP into **one** file, the batch files added
without paths, and the archive is encrypted with asymmetric **PGP** (another method of at least
equal strength only by agreement with GSFT).

- the ZIP is deterministic (decree file order DIW, DIC, DIA, DIL, DII; entry time fixed at the ZIP
  epoch), so its SHA-256 can be attested before encryption;
- PGP is binary OpenPGP, AES-256 with an integrity packet, to the first encryption key of GSFT's
  ASCII-armoured public key ring;
- the key comes from configuration (`openbank.gsft.pgp-public-key`, `Optional`) and is never
  committed; tests generate a throwaway key per run.

**Unverified:** the archive name (`GSFT<bank code><branch>.<batch>.zip` here), the symmetric
cipher and binary-vs-armoured output are not stated by the annex — confirm with GSFT before the
first live delivery.

## Not yet here

Data sourcing from the account register and balances, four-eyes attestation and filing, the scheduled TEST dry run, and temporarily high
balances are follow-ups on #15356.
