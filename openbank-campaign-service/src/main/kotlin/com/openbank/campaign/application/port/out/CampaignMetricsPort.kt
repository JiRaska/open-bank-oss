// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.port.out

import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.EnrolmentState
import java.time.Duration

/**
 * Outbound observability port (ADR-0002 / ADR-0077, issue #5705 item 4). campaign-service was
 * scraped, Ready and Healthy while emitting **no application metric of any kind** — there was no
 * series to query, so nothing could distinguish "campaigns are running" from "campaign-service has
 * contacted nobody since Tuesday".
 *
 * The failure modes these meters make visible are all silent by construction:
 *
 *  - **`openbank.campaign.dry-run` defaults to `true`.** A journey step then runs every gate, writes
 *    a `DRY_RUN` send-log row, and returns [com.openbank.campaign.application.workflow.StepOutcome]
 *    `SENT` — with nothing emitted to notification-service on any channel. An environment that
 *    forgets to set it false contacts nobody and reports success at every layer. This repo has
 *    already shipped that exact shape once (a push adapter whose disabled path returned
 *    `success = true`, so every push in an environment with no APNs credentials was counted as
 *    delivered), which is why [SendHandoffOutcome.DRY_RUN] is its own value and never a flag shared
 *    with [SendHandoffOutcome.HANDED_OFF];
 *  - a journey is driven by Temporal, so a worker that never picks work up leaves every enrolment
 *    ACTIVE at step 0 forever — no HTTP error, no latency change, no log line;
 *  - a contact gate that denies everything (a consent-service blip mapped to `NO_CONSENT`, a
 *    mis-set frequency cap) suppresses every send while every request still answers 200.
 *
 * Naming: the send meter counts a **hand-off to notification-service**, never a delivery. This
 * service holds no delivery credentials (ADR-0200 D3), so delivery is not a fact it can establish,
 * and a counter that claimed it would be asserting something no code here can observe.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the application layer stays free
 * of the metrics framework, and so the counters are exercised through the real adapter over a real
 * registry in unit tests.
 *
 * Implemented by [com.openbank.campaign.infrastructure.observability.CampaignMetricsAdapter].
 */
interface CampaignMetricsPort {

    /**
     * Record one delivery attempt for a journey step on [channel]. Called for every step that got
     * past the contact gate — including the dry-run path, which is precisely the case that must not
     * be counted as a hand-off.
     */
    fun sendAttempted(channel: Channel, outcome: SendHandoffOutcome)

    /**
     * Record a journey step that resolved **without** attempting a delivery: a policy suppression,
     * an untaken branch, or a campaign that is no longer sending. Disjoint from [sendAttempted] by
     * construction — a step resolves exactly one way, and every call site is on a `return` path.
     */
    fun stepResolved(outcome: StepResolution)

    /** Record one per-party enrolment attempt, from the scheduled sweep or from a trigger event. */
    fun enrolmentRecorded(outcome: EnrolmentAttempt)

    /**
     * Record how long one whole `enrol` sweep took, segment evaluation included. The slow half of
     * this call is the silver-layer segment query, and it is the part that degrades first.
     */
    fun enrolmentBatchCompleted(duration: Duration)

    /**
     * Record an enrolment reaching a terminal [state] — completed, converted, or terminated for a
     * policy reason. This is the series that says journeys are finishing and not merely starting.
     */
    fun enrolmentTerminal(state: EnrolmentState)
}

/**
 * What a delivery attempt actually did. A bounded set — safe as a metric tag.
 *
 * [DRY_RUN] is deliberately NOT a variant of success. `openbank.campaign.dry-run` defaults to true,
 * the send log records `DRY_RUN`, and the activity still returns `SENT`; a counter that folded the
 * two together would report a fully working campaign in an environment that has never emitted a
 * single message.
 */
enum class SendHandoffOutcome {
    /** The request reached notification-service (or the banner placement port) without throwing. */
    HANDED_OFF,

    /** Every gate passed and the transport was deliberately skipped. Nothing left this process. */
    DRY_RUN,

    /** The hand-off threw. A FAILED send-log row was written and the activity rethrows for retry. */
    FAILED,
}

/** How a journey step resolved when no delivery was attempted. A bounded set — safe as a tag. */
enum class StepResolution {
    SUPPRESSED_CONSENT,
    SUPPRESSED_CAP,
    SUPPRESSED_QUIET_HOURS,
    SUPPRESSED_LIST,

    /** Denied by the gate for a reason with no policy mapping — recorded as `FAILED` in the log. */
    SUPPRESSED_OTHER,

    /** The step's branch condition did not hold. Not a suppression: no policy denied anything. */
    SKIPPED_CONDITION,

    /** The party had already done the thing the campaign existed to cause. */
    GOAL_REACHED,

    /** The campaign is PAUSED; the journey waits rather than ends. */
    CAMPAIGN_PAUSED,

    /** The campaign is CLOSED, DRAFT or PENDING_APPROVAL, or the row is gone entirely. */
    CAMPAIGN_CLOSED,

    /** The workflow asked for a step order the campaign definition no longer contains. */
    STEP_NOT_FOUND,
}

/** What one per-party enrolment attempt did. A bounded set — safe as a metric tag. */
enum class EnrolmentAttempt {
    /** A journey was started and the ACTIVE enrolment persisted. */
    STARTED,

    /** The party landed in the control cohort: a completed, observable no-contact assignment. */
    HOLDOUT,

    /** The attempt threw. The sweep counts it per party and moves on rather than aborting the batch. */
    FAILED,

    /**
     * ADR-0269 rule 1: a credit campaign, and this party has not switched `CREDIT_OFFERS` on.
     *
     * Its own value rather than folding into FAILED or a generic consent bucket. "How many people
     * did we decline to offer credit to, and why" has to be answerable from the metrics — the
     * ADR's own success measure is the share of offers shown without a prior customer action, and
     * that cannot be computed from a counter that also holds database errors and marketing opt-outs.
     */
    SUPPRESSED_CREDIT_CONSENT,
}
