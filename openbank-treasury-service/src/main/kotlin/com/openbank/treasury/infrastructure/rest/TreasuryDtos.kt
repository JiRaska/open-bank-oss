// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.treasury.application.port.`in`.CounterpartyExposure
import com.openbank.treasury.application.port.`in`.CurrencyPosition
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.ProductType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Every field nullable: Jackson hands `null` for an absent property, and a non-null Kotlin type
 * would turn a missing field into a 500 instead of a 400. The resource `requireNotNull`s each.
 */
data class DraftDealRequest(
    val product: ProductType? = null,
    val counterpartyId: String? = null,
    val currency: String? = null,
    val principal: BigDecimal? = null,
    val rate: BigDecimal? = null,
    val tradeDate: LocalDate? = null,
    val valueDate: LocalDate? = null,
    /** Omit for overnight (next business day). Ignored for CNB_DEPOSIT_FACILITY, always overnight. */
    val maturityDate: LocalDate? = null,
    val rationale: String? = null,
)

data class ReasonRequest(val reason: String? = null)

data class LimitCheckResponse(
    val currency: String,
    val limit: BigDecimal,
    val exposureBefore: BigDecimal,
    val exposureAfter: BigDecimal,
    val headroomAfter: BigDecimal,
    val breached: Boolean,
) {
    companion object {
        fun from(c: LimitCheck) = LimitCheckResponse(
            c.currency, c.limit, c.exposureBefore, c.exposureAfter, c.headroomAfter, c.breached,
        )
    }
}

data class TransitionResponse(
    val from: DealState?,
    val to: DealState,
    val actor: String,
    val actorType: String,
    val at: Instant,
    val note: String?,
)

data class JournalRefResponse(val event: String, val idempotencyKey: String, val journalId: UUID, val postedAt: Instant)

data class DealResponse(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val rate: BigDecimal,
    val dayCount: String,
    val days: Long,
    val interest: BigDecimal,
    val tradeDate: LocalDate,
    val valueDate: LocalDate,
    val maturityDate: LocalDate,
    val state: DealState,
    val createdBy: String,
    val createdByType: String,
    val submittedBy: String?,
    val approvedBy: String?,
    val rationale: String?,
    val limitCheck: LimitCheckResponse?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val history: List<TransitionResponse>,
    val journals: List<JournalRefResponse>,
) {
    companion object {
        fun from(d: Deal, journals: List<LedgerJournalRef> = emptyList()) = DealResponse(
            dealId = d.id,
            product = d.product,
            counterpartyId = d.counterpartyId,
            currency = d.currency,
            principal = d.principal,
            rate = d.rate,
            dayCount = "ACT/360",
            days = d.days,
            interest = d.interest,
            tradeDate = d.tradeDate,
            valueDate = d.valueDate,
            maturityDate = d.maturityDate,
            state = d.state,
            createdBy = d.createdBy.id,
            createdByType = d.createdBy.type.name,
            submittedBy = d.submittedBy?.id,
            approvedBy = d.approvedBy?.id,
            rationale = d.rationale,
            limitCheck = d.limitCheck?.let(LimitCheckResponse::from),
            createdAt = d.createdAt,
            updatedAt = d.updatedAt,
            history = d.history.map { TransitionResponse(it.from, it.to, it.actor.id, it.actor.type.name, it.at, it.note) },
            journals = journals.map { JournalRefResponse(it.event.key, it.idempotencyKey, it.journalId, it.postedAt) },
        )
    }
}

data class CounterpartyResponse(
    val counterpartyId: String,
    val name: String,
    val kind: CounterpartyKind,
    val synthetic: Boolean,
    val currency: String,
    val limit: BigDecimal,
    val exposure: BigDecimal,
    val headroom: BigDecimal,
) {
    companion object {
        fun from(e: CounterpartyExposure) = CounterpartyResponse(
            e.counterparty.id, e.counterparty.name, e.counterparty.kind, e.counterparty.synthetic,
            e.currency, e.limit, e.exposure, e.headroom,
        )
    }
}

data class CurrencyPositionResponse(
    val currency: String,
    val placed: BigDecimal,
    val borrowed: BigDecimal,
    val atCnb: BigDecimal,
    val net: BigDecimal,
) {
    companion object {
        fun from(p: CurrencyPosition) = CurrencyPositionResponse(p.currency, p.placed, p.borrowed, p.atCnb, p.net)
    }
}

data class PositionsResponse(val asOf: LocalDate, val positions: List<CurrencyPositionResponse>)
