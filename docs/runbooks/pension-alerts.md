# Pension: alerts and dashboards

Operational runbook for the `openbank-pension-alerts` PrometheusRule
(`openbank-infra/gitops/components/observability/prometheus-rules-pension.yaml`, #12424).
Kept here rather than in the generated `svc-pension.md`, which `generate-service-runbooks.py`
owns; hand edits there are drift (#2255).

Dashboards (Grafana, provisioned from `dashboard-openbank-pension-*.yaml`):

- **OpenBank — Pension business**: contracts, onboarding funnel, money in and out, incentives,
  payouts, annuity partners, operational queues.
- **OpenBank — Pension technical**: RED per endpoint, JVM and DB pool, Kafka lag and DLQ, Temporal,
  SCA, idempotency, optimistic-lock conflicts, scheduler liveness, SLO.

## How the signals behave on a fresh pod

The queue, aggregate and contract gauges (`openbank_pension_queue_*`, `openbank_pension_aggregates`,
`openbank_pension_contracts`) are published by the `pension-state-gauges` job, every minute, and only
after it has read its first snapshot. `openbank_pension_state_contribution_deadline_breaches` is
published after the first daily deadline check. Until then the series are **absent**, not zero, so
nothing can fire from a restart, and a blank panel right after a deploy is expected for up to a
minute (a day for the deadline gauge). The "never ran" case is `WorkflowLivenessStale`
(fleet rule) and `PensionStateGaugesAbsent` (below).

Labels never carry a contract, party or payment id. To find the items behind a count, use the
operator API, never the metrics.

## PensionUnmatchedPaymentsAgeing

A payment no contract claims has been parked for more than 3 days. The money is neither invested
nor returned.

1. List the queue: `GET /api/v1/pension/funding/operations/unmatched?status=OPEN` (operator role).
2. Read each `reason`. `UNKNOWN_REFERENCE` / `NO_REFERENCE`: find the contract from the payer and
   amount, then `POST .../unmatched/{id}/assign` with the `contractId`. `CONTRACT_NOT_ACCEPTING`:
   the contract is not ACTIVE / signed; assign only once it is, otherwise
   `POST .../unmatched/{id}/return`. `CURRENCY_MISMATCH`, `EMPLOYER_NOT_AUTHORISED`: return.
3. Every decision records the operator; it cannot be undone, so confirm the contract first.

## PensionPaymentInstructionsStuck

A payout instruction has been PENDING for over 2 hours: the payout was confirmed but the money was
not handed to domestic-payment-service.

1. Check the pension exit Temporal workers (technical dashboard, Temporal row) and
   domestic-payment-service availability.
2. `GET /api/v1/pension/operator/payouts?status=IN_PAYMENT` shows the affected payouts.
3. Do not create a payment by hand: instructions are idempotent on their key and the workflow
   resends once the dependency recovers.

## PensionIncentiveClaimsUnfiled

A state-incentive claim has been PENDING for over 120 days, past its quarterly filing window
(ZDPS §16(2)).

1. `GET /api/v1/pension/funding/operations/claim-batches` and the logs of
   `pension-incentive-claims` / `pension-state-contribution-deadlines`: a warning
   `no claim channel adapter for format` means the format has no adapter in this build.
2. When the adapter exists, run a claim run: `POST .../claim-runs`. Filing is atomic; a rerun
   never files a claim twice.

## PensionStateContributionReturnReportDue

State-contribution returns are DUE between the 7th and the 10th. The monthly return report is due
by the 10th (ZDPS §18(4)). The daily deadline job files it.

1. Confirm the job ran: technical dashboard, scheduler liveness, workflow
   `pension-state-contribution-deadlines`.
2. If it did not, file now:
   `POST /api/v1/pension/funding/operations/state-contribution/return-reports`.
3. `GET /api/v1/pension/funding/operations/state-contribution/deadlines` shows what is still
   outstanding.

## PensionStateContributionDeadlineBreached

Critical. The daily check found an item already past a regulatory deadline (`kind`:
`claim_filing`, `claim_payment` or `return_due`).

1. `GET /api/v1/pension/funding/operations/state-contribution/deadlines` lists the late items.
2. `claim_filing` / `return_due`: file now (see the two sections above). Record the lateness
   for compliance; the filing is accepted late but the delay is reportable.
3. `claim_payment`: the agency has not paid a filed claim. Check the receipt file was applied
   (`POST .../claim-batches/{id}/receipt`) before contacting the agency.

## PensionPaymentEventDeadLettered

A domestic-payment status change could not be applied to a pension payout instruction after
retries and was parked on `openbank.dlq.pension.domestic-payment-events-in`. The DLQ has no
consumer; the payout stays in its previous state. The alert counts retained records from each
partition's current and oldest offsets, so it remains active beyond the first hour. Replaying
the event does not remove the DLQ record; verify the payout state separately, and keep the
record available for investigation until its normal retention expires.

1. Read the record (key = payment reference) from the DLQ topic.
2. Fix the cause (usually the pension database or an unknown payment reference), then replay the
   record onto `openbank.domestic.payment.events`. Settlement is applied by state transition, so a
   replay of an already-applied status is a no-op.

## PensionNotificationsFailing

Over 20% of participant notices were refused by the broker for 15 minutes. `skipped` notices do
not count: they mean `openbank.pension.notifications.enabled` is off, which is the default until
notification-service carries the pension templates (#12392).

1. Check Kafka health and the pension producer's ACL on the notification request topic.
2. Notices are statutory for payout-account changes; see the next section for their effect.

## PensionPayoutAccountChangeNoticeFailed

A participant's SCA-confirmed payout-account change was stored but its notice failed, so the
change is held and cannot take effect.

1. Fix the notification path (previous section).
2. Do not mark a change as notified by hand (no route does this on purpose): the notice is the
   participant's protection against an account change they did not make. Once notices flow, the
   participant repeats the change under a fresh SCA challenge.

## PensionScaUnavailable

sca-service cannot answer challenge consumes. Every pension operation that needs SCA (signing,
transfers, payouts, annuity selection, schedule and beneficiary changes) fails closed with 503.

1. Check sca-service health and the pension OIDC client (token minting for the REST client).
2. Refused challenges (`outcome="refused"`) are participant errors, not an outage, and do not fire
   this alert.

## PensionAnnuityPartnerFailing

An annuity partner errored or timed out on every quote in the last hour. Participants see no offer
from it. A partner answering with no valid offer is `no_valid_offer` and does not fire.

1. `GET /api/v1/pension/operator/annuity-providers/{partnerId}` for its adapter and endpoint.
2. If the partner is down for longer, disable it (`POST .../{partnerId}/disable`, four-eyes
   applies on reactivation) so participants are not offered a partner that cannot quote.

## PensionStateGaugesAbsent

Pension pods are up but no `pension-state-gauges` heartbeat exists, so every queue and deadline
alert above has no data. Usually a build without the job, or the scrape dropping the family.

1. `/q/metrics` on the management port of a pension pod: look for
   `openbank_workflow_last_success_age_seconds{workflow="pension-state-gauges"}`.
2. If present there but not in Prometheus, check the PodMonitor for the `pension` namespace.

## Pension fund

Alerts of the `openbank-pension-fund-alerts` PrometheusRule
(`openbank-infra/gitops/components/observability/prometheus-rules-pension-fund.yaml`, #12424) for
`openbank-pension-fund-service` (namespace `pension-fund`).

Dashboards: **OpenBank — Pension fund administration** (NAV pipeline and cutoff, AUM and flows per
fund, orders, four-eyes approvals) and **OpenBank — Pension fund technical** (RED, spans, JVM, price
feed, optimistic-lock conflicts, replays, scheduler liveness, logs).

### How the fund signals behave on a fresh pod

The NAV-age, pending-order, AUM, units and approval-queue gauges are published by the
`pension-fund-state-gauges` job every minute, and only after its first snapshot. Until then they are
**absent**, not zero, so nothing fires from a restart. A fund that has never published a NAV has no
net-assets, NAV-per-unit or last-published series at all; its units outstanding (0) and pending
orders are measured. The `fund` label is the fund's ISIN (public, bounded); no contract, order or
NAV id is ever a label. Find the items behind a count with the API, never the metrics.

Depositary reconciliation is not modelled by the service, so there is no reconciliation-break signal.
Reconcile units outstanding and the last published net assets against the depositary statement out of
band.

### PensionFundNavNotPublishedByCutoff

The latest published NAV of a fund is older than the cutoff: the NAV of business day T is due by
12:00 UTC on the next business day (Tue-Sat: yesterday's; Sunday and Monday: Friday's). Orders of the
fund cannot settle until a NAV is published.

1. `GET /api/v1/funds/{fundId}/navs`: is a NAV for the day `CALCULATED` and waiting for its checker?
   Then it is an approval problem: a second operator publishes it (`POST /api/v1/navs/{navId}/approve`)
   or rejects it. The maker cannot.
2. Nothing calculated: the calculation failed or was never run. Check the price feed
   (`PensionFundPriceFeedFailing`, the "Market-price lookups by outcome" panel); positions can be
   priced manually in the calculation request.
3. Public holidays are not modelled: on the business day after one, the rule fires once. Silence it
   for that fund until the next cutoff rather than editing the rule.

### PensionFundNavCorrectionIssued

A corrected NAV superseded a published one and re-priced every transaction settled at it (the
"Correction re-pricing volume" panel shows how many). A NAV error affects participants who traded at
the wrong price.

1. Find the correction: `GET /api/v1/funds/{fundId}/navs` (the new record carries `correctsNavId`).
2. Record the cause and the size of the error; decide with compliance whether participants and the
   supervisor must be informed. The re-priced transactions carry `correctedFromNavId`.

### PensionFundNavApprovalAging

A calculated NAV has waited more than 4 hours for its four-eyes checker. Publish or reject it from a
second operator identity; a stale CALCULATED NAV also blocks a new calculation for the same day.

### PensionFundOrdersPendingBeyondSettlement

An order of a fund has been PENDING for more than 4 days. Orders settle at the next NAV published
after they were placed; one pending this long means no NAV has been published since (see the cutoff
alert for the same fund) or the publication failed to settle it.

1. Check the fund's latest NAV and its publication time against the order's `placedAt`
   (`GET /api/v1/contracts/{contractId}/orders`).
2. If NAVs were published after the order and it is still PENDING, treat it as a defect: capture the
   order and NAV ids and open an issue; do not settle by hand.

### PensionFundStrategyChangeApprovalAging

A strategy change has waited more than 5 days for approval. The participant notice period runs from
approval, so the earliest effective date slips by every day it waits. Approve or reject it from a
second operator (`POST /api/v1/strategy-changes/{id}/approve` or `/reject`).

### PensionFundPriceFeedFailing

Every market-price lookup in the last hour threw: the feed is unreachable or broken. NAVs needing a
feed price cannot be calculated. A lookup answered with no price (`outcome="missing"`) is a data gap
for that instrument and day, not an outage, and does not fire this alert.

1. Check the market-price adapter's configuration and the vendor endpoint (the shipped adapter is a
   stub; a deployment binds its own).
2. If the cutoff is at risk, calculate with manually supplied prices from an independent source and
   record the source.

### PensionFundStateGaugesAbsent

Pension-fund pods are up but no `pension-fund-state-gauges` heartbeat exists, so the cutoff, order
and approval alerts have no data.

1. `/q/metrics` on the management port (8090) of a pension-fund pod: look for
   `openbank_workflow_last_success_age_seconds{workflow="pension-fund-state-gauges"}`.
2. If present there but not in Prometheus, check the PodMonitor for the `pension-fund` namespace.
