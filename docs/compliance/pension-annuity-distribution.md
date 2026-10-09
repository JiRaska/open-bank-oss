# Pension annuity marketplace — regulatory notes

> **STATUS: DRAFT FOR LEGAL REVIEW.** Nothing in this note is legal advice or a legal opinion. It
> records the assumptions the implementation (#12383, ADR-0334) is built on so that counsel can
> confirm or correct them before any partner insurer is activated in production. Every statement
> marked **[LR]** needs explicit legal sign-off.

## What the platform does

At retirement, a participant who chooses the ANNUITY payout form can ask every *eligible* partner
insurer for an offer. The platform presents the offers side by side, the participant selects one
under strong customer authentication, and the platform transfers the single premium from the
pension contract to the chosen insurer, which issues the policy. The platform never prices,
underwrites or carries the annuity risk.

## Insurance distribution role (IDD) [LR]

- Presenting annuity offers from several insurers and facilitating the conclusion of the contract
  is very likely **insurance distribution** under the Insurance Distribution Directive
  (Directive (EU) 2016/97, Art. 2(1)(1)) and the national transposition (in Czechia, Act
  No. 170/2018 Coll. on insurance and reinsurance distribution). The provider operating the
  platform therefore needs an appropriate **distributor registration** (e.g. tied or independent
  intermediary, or the exemption that applies to it) before the marketplace goes live. **[LR]**
- The registry stores each partner's **licence reference and supervisor** and its **legal entity**
  (party-service reference). Verifying the licence against the supervisor's public register is an
  operator step at activation today; automating it is a follow-up. **[LR]**
- Whether the platform acts on a **fair and personal analysis** basis (IDD Art. 20(3)) or for a
  limited panel of insurers must be stated to the participant; the response's disclaimer and the
  registry's list of active partners are the inputs for that statement. **[LR]**

## Information duties [LR]

- Before the selection the participant must receive the insurer's pre-contractual information
  (IDD Art. 18–20; the insurance product information requirements of the national law). The
  platform currently shows the normalised offer fields; **the partner's own pre-contractual
  documents are not yet attached to an offer** — a follow-up must add them (the reference protocol
  has room for a document link) before production use. **[LR]**
- Demands-and-needs: the payout-form choice and the annuity preferences (type, guarantee,
  joint-life) are captured; whether this satisfies the demands-and-needs test of IDD Art. 20(1)
  for this product is for counsel to confirm. **[LR]**
- Remuneration disclosure (IDD Art. 19): if the platform receives any remuneration from a partner,
  it must be disclosed; **the platform must not use remuneration to rank offers** (see below).
- Cooling-off: the registry records each partner's cooling-off period; the participant can cancel
  within it through the platform. The refund goes to the participant's signed payout account,
  because by then the pension contract is paid out. **[LR]** (confirm that the pack's
  "premium back to the contract" rule does not also apply to a cooling-off cancellation).

## Comparison fairness — no steering

- Offers are listed in **one fixed, disclosed order**: annuity type, then the highest monthly
  amount, then partner id as a neutral tie-break (`presentationOrder` in every response).
- No offer is marked "recommended"; no partner attribute other than the offer's own figures
  (no remuneration, no commercial relationship) influences the order.
- Every eligible partner is asked the **same question**; a partner that fails, times out or returns
  an offer that does not answer the request is **listed as a partner failure**, so a missing offer
  is visible rather than silently absent.
- Offers are **normalised** (monthly amount, guarantee period, indexation, survivor share, one-off
  fee, yearly fee, validity, and a `guaranteedTotal`) so they compare like for like.
- Offers marked `illustrative` are not a binding price; the reference SIMULATOR partner (dev/test
  builds only) always marks its offers illustrative and its pricing ignores mortality.

## Money flow and failure handling

- The premium is sent **only after** the insurer accepted the application; a premium that could
  not be sent cancels the application (premium not sent → no policy).
- A refused policy, or a premium that did not buy one, is returned **to the contract** (re-invested)
  or **to the client** according to the jurisdiction pack (`exit.annuity.refusedPremiumDestination`;
  CZ DPS: contract). Withholding tax already remitted on that premium is not reversed automatically
  (CZ DPS annuity is untaxed at source, so the case does not arise there). **[LR]**

## Data protection [LR]

The quote request carries the participant's date of birth (and, for joint-life, the second life's)
and a pseudonymous holder reference. Identity data for policy issue is exchanged under the partner
agreement. A data-processing / joint-controller assessment per partner is required before
activation. **[LR]**
