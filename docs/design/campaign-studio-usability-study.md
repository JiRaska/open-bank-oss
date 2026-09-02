# Campaign Studio usability study protocol

> Status: planned evidence collection. This protocol is not a usability result, a production-readiness
> attestation, or permission to change a campaign.

## Purpose

Issue [#4476](https://github.com/JiRaska/open-bank-oss/issues/4476) requires evidence that a
marketer can understand and review an app-first campaign journey without assistance. Automated UI
and Playwright contracts prove the software's behaviour; they cannot establish comprehension,
time-on-task, or the point at which an operator becomes uncertain. This protocol makes that missing
evidence repeatable without recording customer data.

## Scope and guardrails

- Recruit representative marketing operators who are authorised to use a non-production Campaign
  Studio environment. Do not recruit customers and do not use a live campaign or a live audience.
- Use seeded, synthetic campaign names, audiences, content and outcomes only. Do not record a
  participant's credentials, screen contents beyond the study task, customer identifiers, free-text
  campaign content, or browser telemetry unrelated to the task.
- The facilitator explains the task once, then does not teach product concepts or correct choices
  during the attempt. A participant may stop at any time.
- A pass means the participant reaches the stated safe end state unaided. It does not authorise
  submission, approval or activation of a campaign.

## Participants and sample

Run the study with at least five representative marketers, including at least one operator who did
not participate in the Studio's implementation. Record role band and prior Campaign Studio
experience as `new`, `occasional`, or `regular`; record no names or account identifiers in the
repository. If roles require it, a compliance observer may attend without interacting.

The named study owner stores completed, access-controlled raw notes outside this public repository.
Access is limited to that owner and the accountable product owner; observers receive only the
anonymised aggregate. Delete the participant-level notes, timing rows and recordings at the earlier
of 90 days after the aggregate evidence and its follow-up dispositions are recorded, 180 days after
the participant's session, or immediately after a participant withdraws. An unresolved follow-up
does not extend the 180-day maximum. Only the anonymised aggregate below may be linked from an
issue or pull request.

## Environment

Before every session, the facilitator verifies:

1. a disposable non-production tenant and synthetic audience are selected;
2. the seeded programme/campaign uses no real customer, product, offer, referral or incentive data;
3. the participant has only the read/create permissions needed for the scenario; and
4. no submission, approval or activation action is available at the end of a task.

The facilitator records the deployed admin-ui image and Campaign Service image separately. A
GitOps desired image is configuration evidence only; it is not evidence that the tested screen was
live.

## Tasks

Give tasks in this order and do not reveal the expected UI control names.

| Task | Safe end state | What it tests |
| --- | --- | --- |
| 1. Compose | Select the seeded approved audience, choose an app surface and a closed destination, then explain what the customer will see. | One canvas, mobile-first preview and app-surface comprehension. |
| 2. Review | Identify reach qualification, consent/frequency suppressions, and every launch blocker; explain why a policy suppression is not a delivery failure. | Readiness, safe defaults and truthful delivery language. |
| 3. Measure | From a seeded active campaign, distinguish sent, impression/click/dismiss and authoritative conversion; state which outcome counts as a conversion. | Outcome/attention separation and no fake success claim. |
| 4. Decide | Compare a seeded holdout or content experiment and state the next human action without declaring an automatic winner. | Experiment interpretation and governed decision ownership. |

For every task, the facilitator asks one neutral prompt after the participant stops: “What, if
anything, made you uncertain?” The answer is categorised using the taxonomy below rather than
quoted verbatim unless the participant expressly consents to a de-identified quotation.

## Measures

Record one row per participant and task:

- `completion`: `unaided`, `completed_with_facilitator_clarification`, `not_completed`, or
  `stopped`;
- `time_seconds`: start when the task is read and stop at the safe end state or stop decision;
- `wrong_turn_count`: attempts that would lead to a different customer surface, an unsafe launch
  conclusion, or an incorrect performance interpretation;
- `uncertainty_tags`: zero or more from `audience`, `entry`, `surface`, `destination`, `consent`,
  `frequency`, `readiness`, `suppression`, `measurement`, `experiment`, `approval`, `navigation`,
  or `other`; and
- `observed_issue`: a short de-identified description of the screen or concept, never a customer
  record or participant identity.

Compute and publish only aggregate evidence: number of sessions, unaided completion rate by task,
median and range of time-on-task, the count of clarification requests, and ranked uncertainty tags.
Do not publish individual timings when the sample or role mix could re-identify a participant.

## Success and follow-up rule

The acceptance signal is not a single average. The study is ready to support a default-rollout
decision only when all four tasks have at least 80% unaided completion, no participant mistakes a
suppression, impression, click, or reservation for an authoritative conversion, and no recurring
uncertainty tag affects two or more participants without a documented disposition.

If the signal is not met, create or link a focused issue for the recurring problem, implement and
test the change, then repeat only the affected task with new representative participants. Do not
silently retest the same participant until the metric passes, and do not replace the study with a
component or E2E test.

## Evidence record

After the study, add an anonymised issue comment with this template:

```text
Campaign Studio usability study
- environment: non-production; admin-ui image <actual image>; campaign-service image <actual image>
- sessions: <n>; role mix: <aggregate only>
- task completion: compose <x/n>, review <x/n>, measure <x/n>, decide <x/n> unaided
- median time seconds: compose <n>, review <n>, measure <n>, decide <n>
- recurring uncertainty tags: <tag: count, ...>
- conversion/suppression safety errors: <count and disposition>
- follow-up issues or decision: <links>

No customer data, participant identifiers, credentials, campaign content, or raw recordings are
stored in this repository.
```

The issue remains open until that evidence exists and its linked follow-ups are resolved or
explicitly accepted by the accountable product owner.
