# Pension: client flow spec

The ONE client-facing flow contract for the pension lifecycle (ADR-0334). It says what the app
shows and which route backs it, not pixels. The app talks only to `openbank-customer-edge` under
`/customer/v1/pension`; the edge relays to `openbank-pension-service` (the routes named below
are pension-service's, under `/api/v2/pension`). Domain truth lives in ADR-0334 and the services'
`openapi.yaml`; when this doc and the ADR disagree, the ADR wins. This file supersedes
`docs/ux/pension-onboarding-questionnaire.md` (F7) and `docs/ux/pension-lifecycle.md` (#12359,
which should drop its copy when it rebases onto this one).

The legal research behind the questionnaire is in
[`docs/research/cz-pension-onboarding-and-questionnaire.md`](../research/cz-pension-onboarding-and-questionnaire.md);
the state contribution in [`docs/research/cz-state-pension-contribution.md`](../research/cz-state-pension-contribution.md).
The Czech question sets and packs are **REQUIRES_LEGAL_REVIEW** until compliance confirms them.

## Ground rules

- **A pension is personal.** Every route acts for the signed-in person's own party; another
  party's contract, application or payout is a 404, indistinguishable from one that does not exist.
- **Every POST that changes something sends an `Idempotency-Key`** per user intent, reused on retry.
- **Money is a decimal string.** Never parse it to a float.
- **Every state change needs SCA.** Two shapes:
  - *Edge-bound* (pause / resume, application withdrawal): the edge answers 403 `SCA_REQUIRED` with
    `scaLinking`; raise the challenge with it and repeat the identical request.
  - *Document-bound*: pension-service consumes the challenge itself. Raise an sca-service
    `APPROVAL` challenge with exactly the `approvalRequestId` and `payloadSha256` below — the
    `pension-` namespace is reserved for pension-service (ADR-0335), so a challenge raised with any
    other id is refused, and one raised for one operation cannot be spent on another.

| Operation | Route | `approvalRequestId` | `payloadSha256` |
|---|---|---|---|
| Sign the application | `POST /onboarding/applications/{id}/sign` | `pension-onboarding:{applicationId}:{documentId}` | the key-information document's SHA-256 |
| Transfer out | `POST /transfers/out`, `POST /transfers/{id}/consent` | `pension-transfer-out:{contractId}:{providerId}:{contractNumber}` | SHA-256 of that id |
| Termination, payout confirm, payout-account change | `.../termination/{n}/sign`, `.../payouts/{p}/confirm`, `PUT .../payouts/{p}/account` | `pension-exit:{hash}` | `hash` = the quote's signing hash (covers quote AND payout IBAN) |
| Contribution schedule change | `POST /contracts/{id}/contribution-schedule/changes` | `pension-schedule-change:{documentSha256}` | `documentSha256` from the preview |
| Beneficiary change | `POST /contracts/{id}/beneficiaries/changes` | `pension-beneficiary-change:{documentSha256}` | `documentSha256` from the preview |
| Contribution mandate set-up | `POST /funding/contracts/{id}/mandates` | `pension-mandate-setup:{hash}` | `hash` = SHA-256 of `pension-mandate-setup\|contractId\|kind\|IBAN\|amount\|CURRENCY\|firstCollection` |
| Contribution mandate cancel | `POST /funding/contracts/{id}/mandates/{m}/cancel` | `pension-mandate-cancellation:{hash}` | SHA-256 of `pension-mandate-cancel\|contractId\|mandateId` |
| Annuity selection / cancellation | `.../annuity/selection`, `.../annuity/cancellation` | `pension-annuity-selection:{hash}` / `pension-annuity-cancellation:{hash}` | the selection / cancellation hash the offer response carries |

A challenge is spent once; a refused or spent one is a 403 (exit, changes, mandates) or 422
(onboarding sign). Ask for a new one, never retry the same id.

## 1. Discover and simulate

Product list and detail (`GET /products`), simulator (`POST /simulations`, always illustrative,
with the returned disclaimer). Values carry `reviewStatus`; while it is illustrative every screen
shows an "illustrative, not an offer" banner.

## 2. Onboard (new contract or transfer-in)

1. **Eligibility and identity** come from KYC; birth date and residency are never asked.
2. **Open the application**: `POST /onboarding/applications` (`kind` `NEW_CONTRACT` or
   `TRANSFER_IN` with the ceding contract).
3. **Questionnaire** (below).
4. **Strategy**: `POST .../strategy`; omit `strategyCode` to accept the recommendation. A
   riskier choice needs its warnings acknowledged first (below).
5. **Key-information document**: show it, then `POST .../kid/accept`.
6. **Sign** with the document-bound challenge. The contract activates after the cooling-off period
   (`coolingOffEndsOn`) and, for DPS, on the first contribution; within cooling-off the customer may
   withdraw (edge-bound SCA).

## 3. Questionnaire

### Questionnaire principles

1. **Don't ask what we already know.** Identity, birth date and residency come from KYC (BankID-style
   identity reuse at the edge). The investment horizon is worked out from the birth date and the
   pack's retirement age. It is shown to the participant ("about 24 years to retirement") and never
   asked as a question.
2. **One question per idea, closed answers only.** Nothing is scored from free text. Risk questions
   describe a situation ("your savings fall 20 %, what do you do?") instead of asking for a
   self-label ("are you dynamic?"), as the ESMA suitability guidelines recommend.
3. **Show money, not percentages.** When the participant gives a savings band, every loss-capacity
   option shows its amount in CZK (`illustrationCzk`).
4. **The weakest answer decides, and we say which one.** The profile screen names the answers that set
   the risk class (`why`). It does not show a score.
5. **Never block silently, never warn silently.** If a choice needs a warning, the warning gets its own
   screen with an explicit "I understand" action. Under DPS rules the participant may still choose
   (ZDPS § 136(3)). Under MiFID advice for a DIP, an unsuitable strategy is not offered for
   acknowledgement at all.
6. **Save as you go.** Each step saves a draft (`PUT .../questionnaire/draft`). Leaving and returning
   resumes at `progress.nextStep`.
7. **Sustainability is optional and plain.** It is a separate step that can be skipped. It offers five
   plain choices, each mapped to the regulatory categories (taxonomy share, SFDR share, PAI). Skipping
   it is recorded as "no preference", never guessed.

### DPS (supplementary pension savings): 5 screens

| # | Screen | Content | API |
|---|---|---|---|
| 1 | Goal | Objective (4 options) | `GET .../questionnaire`, `PUT .../draft` |
| 2 | Risk | Reaction to a 20 % fall; one factual check question; experience with funds | `PUT .../draft` |
| 3 | What you can bear | Optional savings band; loss capacity shown in CZK | `PUT .../draft` |
| 4 | Sustainability (optional) | Five plain choices plus "Skip" | `PUT .../draft` |
| 5 | Your profile and strategy | Risk class 1-7 with label, the answers that set it, the years to retirement, the recommended (lifecycle) strategy, and "choose another" | `POST .../questionnaire`, `GET .../profile` |

That is 6 required questions and 1 optional one. Next come the key-information document and the SCA
signature, which are existing steps.

- **Contradictions.** If `POST .../questionnaire` answers **422** with `inconsistencies`, show both
  conflicting answers side by side with the message from the question set. The participant either
  changes one answer or confirms both. Confirming resubmits with `confirmInconsistencies`, and the
  more conservative answer still decides the class.
- **Choosing a riskier strategy.** Call `GET .../warnings?strategyCode=X`. If it returns warnings,
  show each one on its own screen, then call `POST .../warnings/acknowledge` with the codes shown
  and the language, and only then `POST .../strategy`. The server stores the hash of the exact text
  shown with the acknowledgement. Without it the strategy call is a **400** and signing is a **409**.

### DIP (long-term investment product, MiFID): 5 steps plus profile

The steps are Goal, Finances (shock-expense question, optional savings band, loss capacity), Risk,
Knowledge and experience, and Sustainability (optional). Knowledge and experience are asked for
**bond funds and equity funds**. Knowledge uses factual questions that include an "I don't know"
option, and experience asks how often the participant has invested in the last 3 years. Together
they decide **appropriateness**:

- **Inappropriate.** The `PRODUCT_NOT_APPROPRIATE` warning is shown and must be acknowledged.
- **Unsuitable (riskier than the profile).** The strategy is not offered for acknowledgement: the
  call answers **400**, which is the MiFID suitability rule.
- **Sustainability preference not met.** If no offered strategy matches the stated preference, the
  `SUSTAINABILITY_PREFERENCE_NOT_MET` warning records that the participant adapted the preference.


## 4. Contract overview and contributions

- `GET /contracts`, `GET /contracts/{id}`: status badge (`PENDING_ACTIVATION`, `ACTIVE`,
  `SUSPENDED` = "contributions paused", `TERMINATING`, `PAID_OUT`, `TRANSFERRED_OUT`, `CLOSED`),
  value, holdings, pending orders.
- One-off payment: `GET /funding/contracts/{id}/payment-reference` — the reference the bank
  matches the payment on.
- Regular payment: `POST /funding/contracts/{id}/mandates` (standing order or SEPA direct debit),
  document-bound SCA above. The debit account must be the customer's own active account; anything
  else is a 403, and the app never sends an account id.
- State contribution (CZ DPS): shown per tax year (`GET .../tax-years/{year}`). It is claimed
  **quarterly**, in the month after the quarter (ZDPS § 16), so a contribution appears as "claimed"
  up to four months later; a returned contribution (§ 18) is shown with its reason.

## 5. Change an existing contract

| Action | Route | What the customer sees |
|---|---|---|
| Strategy | `PUT /contracts/{id}/strategy` | Effective date never in the past. A **409 `REASSESSMENT_REQUIRED`** (`reason`, `applicationId`) means the questionnaire must be answered again first: open it on `applicationId` (prefilled), submit, then repeat the change. |
| Contribution schedule | `POST .../contribution-schedule/preview`, then `.../changes` with the challenge | The change applies from the next collection cycle; lowering below the state-contribution threshold needs an explicit acknowledgement. |
| Beneficiaries | `POST .../beneficiaries/preview`, then `.../changes` with the challenge | Shares total exactly 100; a registered death claim freezes the designation. |
| Pause / resume contributions | `POST .../suspend`, `POST .../resume` | Pausing keeps the contract and its units. |

### Re-assessment

- An assessment is valid for `validityDays` from the pack (365 for CZ). After that the profile
  answers **409** and the UI restarts the questionnaire, prefilled from the previous answers.
- A strategy change of an ACTIVE contract is judged against the assessment in force
  (`ReassessmentPolicy`): expired, superseded, missing, or a strategy above the profile's risk class
  answers 409 `REASSESSMENT_REQUIRED`. Re-answering on the activated application re-assesses the
  contract: same scoring, the lifecycle does not move.
- Answering again **supersedes** the previous assessment; superseded assessments are kept for audit.

## 6. Exit

- **Early termination**: quote (every line shown: redemption value, fees, incentive return,
  net payout, `quoteExpiresAt`), then sign with the payout IBAN. An expired quote is a 409.
- **Payout** (`LUMP_SUM`, `PHASED_WITHDRAWAL`, `FIXED_PERIOD_PENSION`, `ANNUITY`): quote, then
  confirm with the payout IBAN. A 400 `PENSION_RULE_REFUSED` means the pack's conditions are not met.
- **Annuity**: request offers, show every partner's offer side by side (never pre-select), select
  one with SCA; the purchase can be cancelled within the partner's cooling-off.
- **Changing the payout account** of a running payout: `PUT .../payouts/{p}/account`. The change is
  held three days and announced by a security notice; `pendingAccountLast4` shows it and the next
  instalment may still go to the old account. A second change while one is pending is a 409.
- **Death**: no customer flow; the back office handles the claim.

## 7. Notices

pension-service sends notices (payout executed, account changed, strategy effective, transfer status,
state contribution received / returned) through notification-service in the participant's language
(`cs` / `en`). Notices are off until `openbank.pension.notifications.enabled` is switched on per
environment.

## Copy and language

- Use the informal *vy*, short sentences and no jargon. Use "kolísání" rather than "volatilita".
- Every risk question has a one-line help text that explains why it is asked.
- Warnings say what happens and what we recommend. They never blame the participant.
- Both cs and en texts come from the question set, never from the UI bundle. The question set is the
  versioned, legally reviewed source.


## Not covered yet

- The participant refusing to answer (ZDPS § 136(4)) needs a legally reviewed path. Today an
  unanswered questionnaire cannot proceed.
- The lifecycle default is pack data (`lifecycleDefault`). The statutory lifecycle default is still a
  bill (see the research note), so the pack must be updated when it becomes law.
- Prefilling income or savings from bank data needs a consent-scoped source that does not exist yet.
  Until then the savings band is asked, and it is optional.
