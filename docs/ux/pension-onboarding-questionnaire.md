# Pension onboarding questionnaire: UX flow spec

Status: draft for the customer UI (branch for #12359). Issue #12384, ADR-0334. The legal research
behind it is in [`docs/research/cz-pension-onboarding-and-questionnaire.md`](../research/cz-pension-onboarding-and-questionnaire.md).
The Czech question sets are **REQUIRES_LEGAL_REVIEW**: the wording, scores and risk-class ceilings
are reference data until compliance confirms them.

## Principles

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

## DPS (supplementary pension savings): 5 screens

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

## DIP (long-term investment product, MiFID): 5 steps plus profile

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

## Re-assessment

- An assessment is valid for `validityDays` from the pack (365 for CZ). After that, `GET .../profile`
  answers **409** and the UI restarts the questionnaire, prefilled from the previous answers
  (`prefill.previousAnswersAvailable`).
- The domain also triggers a refresh on a strategy change above the profile and on a reported life
  event (`ReassessmentPolicy`). Wiring these to the contract's strategy-election endpoint is a
  follow-up.
- Answering again **supersedes** the previous assessment. Superseded assessments are kept for audit.

## Copy rules

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
