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

/** A binding to a versioned segment artifact (ADR-0201 D1): never a query, always name@version. */
data class SegmentRef(val name: String, val version: Int) {
    init {
        require(name.isNotBlank()) { "segment name must not be blank" }
        require(version >= 1) { "segment version must be >= 1" }
    }
}
