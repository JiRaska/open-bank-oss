// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.marketdata

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class RateSheetStatus { PENDING, ACTIVE, REJECTED, SUPERSEDED }

/** One published rate: a label (a tenor, a product line) and a percentage. */
data class RateSheetRate(val label: String, val value: BigDecimal) {
    init {
        require(label.isNotBlank() && label.length <= MAX_LABEL) { "a rate label is 1..$MAX_LABEL characters" }
        require(value.abs() <= MAX_ABS_PERCENT) { "a rate is a percentage within ±$MAX_ABS_PERCENT" }
        require(value.scale() <= MAX_SCALE) { "a rate has at most $MAX_SCALE decimal places" }
    }

    companion object {
        const val MAX_LABEL = 64
        val MAX_ABS_PERCENT = BigDecimal("100")
        const val MAX_SCALE = 6
    }
}

/**
 * Contract 8.5: an internal rate sheet the bank publishes (effective-dated, four-eyes). Proposed
 * PENDING by a person; ACTIVE only once a DIFFERENT human approves it — never its proposer, never an
 * AI agent or a machine. Approving supersedes the ACTIVE sheet of the same name.
 */
data class RateSheet(
    val id: UUID,
    val name: String,
    val effectiveFrom: LocalDate,
    val rates: List<RateSheetRate>,
    val status: RateSheetStatus,
    val proposedBy: Actor,
    val proposedAt: Instant,
    val decidedBy: Actor? = null,
    val decidedAt: Instant? = null,
    val decisionReason: String? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME) { "a rate-sheet name is 1..$MAX_NAME characters" }
        require(rates.size in 1..MAX_RATES) { "a rate sheet carries 1..$MAX_RATES rates" }
        require(rates.map { it.label }.toSet().size == rates.size) { "rate labels must be unique within a sheet" }
    }

    fun approve(actor: Actor, at: Instant): RateSheet {
        decide(actor, "approve")
        return copy(status = RateSheetStatus.ACTIVE, decidedBy = actor, decidedAt = at)
    }

    fun reject(actor: Actor, reason: String, at: Instant): RateSheet {
        decide(actor, "reject")
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "a rejection needs a reason (1..$MAX_REASON)" }
        return copy(status = RateSheetStatus.REJECTED, decidedBy = actor, decidedAt = at, decisionReason = reason)
    }

    private fun decide(actor: Actor, action: String) {
        if (actor.type !=
            ActorType.HUMAN
        ) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action a rate sheet")
        }
        check(status == RateSheetStatus.PENDING) { "cannot $action a $status rate sheet (expected PENDING)" }
        if (actor.id ==
            proposedBy.id
        ) {
            throw FourEyesViolationException("four-eyes: the proposer of a rate sheet must not $action it")
        }
    }

    companion object {
        const val MAX_NAME = 100
        const val MAX_RATES = 200
        const val MAX_REASON = 500

        fun propose(
            name: String,
            effectiveFrom: LocalDate,
            rates: List<RateSheetRate>,
            actor: Actor,
            at: Instant,
        ): RateSheet {
            if (actor.type !=
                ActorType.HUMAN
            ) {
                throw ActorNotPermittedException("a ${actor.type} principal may not propose a rate sheet")
            }
            return RateSheet(UUID.randomUUID(), name.trim(), effectiveFrom, rates, RateSheetStatus.PENDING, actor, at)
        }
    }
}
