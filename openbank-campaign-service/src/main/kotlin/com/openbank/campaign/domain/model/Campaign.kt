// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Campaign aggregate (ADR-0200). A definition — steps, delays, stop conditions — that is executed
 * as one Temporal workflow per enrolled party. Content is composed from the notification-service
 * template catalogue with declared variables; free-form bodies are rejected by construction
 * (ADR-0176 D4 / ADR-0200 D4).
 */
data class Campaign(
    val id: UUID,
    val name: String,
    val goal: String,
    val segmentRef: SegmentRef,
    val steps: List<CampaignStep>,
    val stopCondition: StopCondition? = null,
    val state: CampaignState,
    val createdBy: String,
    val approvedBy: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "campaign name must not be blank" }
        require(steps.isNotEmpty()) { "campaign must have at least one step" }
        require(steps.size <= MAX_STEPS) { "journeys are capped at $MAX_STEPS steps in the first slice" }
    }

    /** DRAFT → PENDING_APPROVAL → ACTIVE ⇄ PAUSED → CLOSED. Terminal states never leave. */
    fun submit(): Campaign {
        require(state == CampaignState.DRAFT) { "only a DRAFT campaign can be submitted for approval" }
        return copy(state = CampaignState.PENDING_APPROVAL, updatedAt = Instant.now())
    }

    fun activate(approver: String): Campaign {
        require(state == CampaignState.PENDING_APPROVAL) { "only a PENDING_APPROVAL campaign can activate" }
        require(approver != createdBy) { "maker/checker: the approver must differ from the creator (ADR-0200 D5)" }
        return copy(state = CampaignState.ACTIVE, approvedBy = approver, updatedAt = Instant.now())
    }

    fun pause(): Campaign {
        require(state == CampaignState.ACTIVE) { "only an ACTIVE campaign can pause" }
        return copy(state = CampaignState.PAUSED, updatedAt = Instant.now())
    }

    fun resume(): Campaign {
        require(state == CampaignState.PAUSED) { "only a PAUSED campaign can resume" }
        return copy(state = CampaignState.ACTIVE, updatedAt = Instant.now())
    }

    fun close(): Campaign {
        require(state == CampaignState.ACTIVE || state == CampaignState.PAUSED) {
            "only an ACTIVE or PAUSED campaign can close"
        }
        return copy(state = CampaignState.CLOSED, updatedAt = Instant.now())
    }

    companion object {
        const val MAX_STEPS = 5
    }
}

enum class CampaignState { DRAFT, PENDING_APPROVAL, ACTIVE, PAUSED, CLOSED }

/**
 * One journey step: a catalogue template with declared variables, delivered on a channel after a
 * delay from the previous step.
 *
 * ADR-0200 D7 shipped this EMAIL-only behind three named blockers. Two have since cleared: the
 * per-channel marketing consent scope exists (`MARKETING_COMMS_PUSH`, ADR-0198 D4) and #1182 is
 * closed — push bodies are generic by construction. IN_APP and SMS remain out for the reasons on
 * [Channel].
 */
data class CampaignStep(
    val order: Int,
    val template: String,
    val channel: Channel,
    val variables: Map<String, String>,
    val delaySeconds: Long,
    /**
     * The ADR-0200 D1 branch condition (#3585): when set, this step runs only if the condition
     * holds, and is otherwise skipped — the journey continues to the next step rather than ending.
     *
     * Defaulted to null, and that default is load-bearing beyond convenience: a step serialized
     * before this field existed — in a campaign row, and in the Temporal history of an in-flight
     * journey — deserializes with no condition, so a running workflow takes exactly the code path
     * it took before. See [StepCondition].
     */
    val condition: StepCondition? = null,
) {
    init {
        require(order >= 0) { "step order must be >= 0" }
        require(delaySeconds >= 0) { "step delay must be >= 0" }
        // The template decides the channel, and the step must agree. Two ways to get this wrong,
        // both silent: an EMAIL step naming a push template renders a one-line title as a whole
        // email, and a PUSH step naming an email template puts the offer body into an APNs payload
        // — the leak #1182 closed. Neither surfaces until a real send.
        require(TemplateCatalog.CHANNEL_OF[template] == null || TemplateCatalog.CHANNEL_OF[template] == channel) {
            "template '$template' renders on ${TemplateCatalog.CHANNEL_OF[template]}, not $channel"
        }
        // Rejected by construction, not validated at the edge: a step that names a template nobody
        // renders, or passes a variable nobody declared, is only discovered while composing the
        // notification — long after the campaign was approved (ADR-0221 D1, ADR-0176 D4).
        //
        // Deliberately NOT requiring every declared variable to be present. That is stricter than
        // the renderer itself (NotificationTemplate.unknownVariables checks only the unknown
        // direction), and it would make a partially-filled draft unrepresentable. Completeness is
        // an authoring rule, enforced by the wizard before submit — see TemplateCatalog.
        require(TemplateCatalog.exists(template)) {
            "unknown template '$template' — ${TemplateCatalog.MARKETING_ONLY_REASON}"
        }
        TemplateCatalog.unknownVariables(template, variables).let {
            require(it.isEmpty()) { "template '$template' does not declare ${it.sorted()}" }
        }
    }
}

/**
 * Delivery channels a campaign step may use.
 *
 * EMAIL and PUSH only, and the omissions are decisions rather than gaps. SMS has no outbound port
 * anywhere in the platform (ADR-0200 D7). IN_APP was *removed* from `NotificationChannel` by #2372
 * because its dispatch branch silently dropped every message; re-adding it needs a terminal-status
 * transition and a wake-signal design, not an enum entry. Listing either here would let a campaign
 * be approved against a channel that delivers nothing — the "appearance of four channels" ADR-0200
 * D7 explicitly refuses.
 */
enum class Channel { EMAIL, PUSH }

/**
 * The campaign's own stop condition (ADR-0200 D1, issue #3585 slice 1), evaluated by the journey
 * workflow BEFORE each step against observable state — never against a fabricated signal. Only
 * conditions backed by data the service already holds are representable: the first (and so far
 * only) one is the party's lifetime SENT count in this campaign, which the send log holds. A
 * `null` stop condition means the journey always runs every step, exactly as before.
 */
data class StopCondition(val maxSendsPerParty: Int) {
    init {
        require(maxSendsPerParty >= 1) { "maxSendsPerParty must be >= 1 — a zero cap sends nothing" }
    }

    /** True when [sendsSoFar] already reached the cap, so the journey must stop before the next step. */
    fun reachedBy(sendsSoFar: Int): Boolean = sendsSoFar >= maxSendsPerParty
}

/**
 * A branch condition on one step (ADR-0200 D1, issue #3585), evaluated by the journey workflow
 * immediately before the step.
 *
 * Like [StopCondition], only conditions backed by data this service already holds are
 * representable. The one observable fact about an earlier step is its `DeliveryStatus` as reported
 * back by notification-service (ADR-0239 D3) — so the vocabulary is named after exactly that and
 * nothing more. There is deliberately no `IF_PREVIOUS_OPENED` or `IF_GOAL_REACHED`: no impression,
 * click or conversion signal exists anywhere in the platform, and a condition nothing can ever
 * make true is the "inauthentic placeholder" ADR-0220 D5 refuses.
 *
 * `CONFIRMED` means the message was reported delivered. It does NOT mean read, and the honest
 * resting state `PENDING` counts as *not* confirmed — so a follow-up gated on
 * [IF_PREVIOUS_NOT_CONFIRMED] will also fire for a send whose outcome has simply not landed yet.
 * That is a real property of an at-least-once feedback channel, not a bug to be papered over: put
 * a delay on the step that is long enough for an outcome to arrive.
 *
 * "Previous" is the most recent send-log row for this party in this campaign at a LOWER step
 * order. A step with no such row has no observable predecessor, so [IF_PREVIOUS_CONFIRMED] does
 * not hold and [IF_PREVIOUS_NOT_CONFIRMED] does.
 */
enum class StepCondition {
    /** Run this step only if the previous step's delivery was confirmed. */
    IF_PREVIOUS_CONFIRMED,

    /** Run this step only if the previous step's delivery was NOT confirmed — the reminder case. */
    IF_PREVIOUS_NOT_CONFIRMED,
    ;

    /** Whether this step runs, given the [previous] step's delivery status (null = no predecessor). */
    fun holdsFor(previous: DeliveryStatus?): Boolean = when (this) {
        IF_PREVIOUS_CONFIRMED -> previous == DeliveryStatus.CONFIRMED
        IF_PREVIOUS_NOT_CONFIRMED -> previous != DeliveryStatus.CONFIRMED
    }
}

/** A binding to a versioned segment artifact (ADR-0201 D1): never a query, always name@version. */
data class SegmentRef(val name: String, val version: Int) {
    init {
        require(name.isNotBlank()) { "segment name must not be blank" }
        require(version >= 1) { "segment version must be >= 1" }
    }
}
