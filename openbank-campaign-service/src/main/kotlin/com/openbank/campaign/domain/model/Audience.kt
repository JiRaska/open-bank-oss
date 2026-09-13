// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.domain.model

import java.time.Instant

/**
 * A marketer-authored audience wraps the same closed [Segment] DSL used by the catalogue.
 *
 * The mutable stage is deliberately separate from [Segment]: a campaign may only ever pin an
 * approved, immutable segment version. No free-form predicates, JSON paths, or SQL enter this
 * model; [Segment]'s constructor remains the one place that validates source support.
 */
data class Audience(
    val segment: Segment,
    val state: AudienceState,
    val createdBy: String,
    val approvedBy: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun submit(actor: String): Audience {
        require(actor == createdBy) { "only the audience maker can submit this draft" }
        require(state == AudienceState.DRAFT) { "only a DRAFT audience can be submitted" }
        return copy(state = AudienceState.PENDING_APPROVAL, updatedAt = Instant.now())
    }

    fun approve(approver: String): Audience {
        require(state == AudienceState.PENDING_APPROVAL) { "only a PENDING_APPROVAL audience can be approved" }
        require(approver != createdBy) { "maker/checker: the approver must differ from the creator" }
        return copy(state = AudienceState.APPROVED, approvedBy = approver, updatedAt = Instant.now())
    }

    companion object {
        fun catalogue(segment: Segment): Audience = Audience(
            segment = segment,
            state = AudienceState.APPROVED,
            createdBy = "catalogue",
            approvedBy = "catalogue",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
    }
}

enum class AudienceState { DRAFT, PENDING_APPROVAL, APPROVED }
