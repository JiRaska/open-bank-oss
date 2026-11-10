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

## Temporarily high balances (`TemporarilyHighBalances`)

Zákon č. 21/1992 Sb. (consolidated text in force from 11 Jan 2026), transposing Directive
2014/49/EU art. 6(2):

- **§ 41e odst. 3** — a *natural person* who documents the conditions of § 41ea and applies
  under § 41eb odst. 1 receives increased compensation (*zvýšená náhrada*) for the documented
  part of the claim, up to the amount by which the claim exceeds the § 41e odst. 2 limit and at
  most the equivalent of **EUR 100 000**, converted at the ČNB rate of the relevant day.
- **§ 41ea odst. 1** — the relevant day must fall **within 3 months** of crediting, i.e. the
  moment the amount first came to the depositor's disposal, of a payment of one of the
  categories a)–j): sale of residential real estate; settlement of community property on
  divorce; insurance benefit for injury, illness, disability or death; inheritance; one-off
  pension settlement; severance; insurance benefit for harm from a crime; damages or surrender
  of unjust enrichment from a crime; compensation for custody, sentence or protective measure;
  monetary assistance to a crime victim. Each has prescribed evidence.
- **§ 41ea odst. 2–3** — the period does not run while the depositor may not dispose of the
  amount (incapacity, notarial or attorney escrow).
- **§ 41eb** — the depositor applies to GSFT on its form within 2 months of the relevant day;
  GSFT decides and pays within 4 months; the bank cooperates on GSFT's request.
- **§ 41e odst. 6** — no increased compensation for § 41f deposits, except notarial, attorney or
  real-estate-agent escrow.

The increase is therefore **claim-based**: the 3-day delivery carries standard compensation
only, and the annex of vyhláška č. 71/2011 Sb. has no field for it in DIC or DIA. The assembly
takes optional `QualifyingCredit`s (party, account, amount, crediting day, event category,
evidence reference, and `appliedOn`, the day the depositor applied to GSFT or null) and records the result on
`InsuredClaim.increasedCompensationCzk` and on `temporarilyHighClaims`,
`temporarilyHighCompensationCzk` and `refusedQualifyingCredits` (carried into `GsftBatch`).
It never changes `compensationCzk`, `T_PAYMENT` or any rendered file.

Each credit's `amount` is the whole payment to the account. It is converted to CZK and
apportioned to the filing holder by account share, with the same split as the balance. Per
(holder, account), the credited part is bounded by that holder's share of the account balance on
the relevant day, so a credit since spent is not paid out of older savings. The party total is
then capped by (balance − standard limit) and by the EUR 100 000 equivalent.

A credit is refused and counted in `refusedQualifyingCredits` — never paid, and never failing the
assembly — when:

- the depositor did not apply, or applied after relevant day + 2 months (§ 41eb odst. 1);
- the depositor is not flagged as a natural person (`Depositor.naturalPerson`, default false);
- the account is synthetic, overdrawn, or not a live account of that depositor;
- the relevant day is outside the window (crediting day .. crediting day + 3 months, inclusive);
- the same holder files the same payment twice — same account, crediting day, amount and
  evidence reference;
- nothing of it remains attributable after the bounds above.

`refusedQualifyingCredits` therefore counts every supplied credit that contributes nothing. With
no credits the result is unchanged.

**Unresolved**

- The end of the 3-month window is computed as `creditedOn.plusMonths(3)`, inclusive; this
  reading of "ve lhůtě 3 měsíců ode dne připsání" is not confirmed against GSFT practice.
- The attribution rule is unverified: bounding a credit by the holder's balance share on that
  account (rather than tracing the credited money) is a conservative reading of "tu část
  pohledávky z vkladu, u které doložila splnění podmínek" (§ 41e odst. 3), not confirmed by GSFT.
- The 2-month deadline is checked against `appliedOn` supplied by the caller; GSFT, not the bank,
  receives the application, so the bank only learns that date on GSFT's request.
- The suspension of § 41ea odst. 2–3 is not computed: the caller must supply as `creditedOn`
  the day the depositor could first dispose of the amount.
- § 41e odst. 6 (no increase for § 41f deposits other than escrow) is not enforced here,
  because this assembly has no § 41f classification; such credits must not be supplied.
- Whether the evidence suffices is GSFT's decision (§ 41eb odst. 2); the evidence reference is
  carried, not judged.
- What the bank must hand over on GSFT's request (§ 41eb odst. 3) has no prescribed format.

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

## Not yet here

Data sourcing from the account register and balances, ZIP/PGP
transport, four-eyes attestation and filing, and the scheduled TEST dry run are follow-ups on #15356.
