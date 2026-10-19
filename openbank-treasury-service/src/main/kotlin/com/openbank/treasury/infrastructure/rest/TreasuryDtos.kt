// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.treasury.application.port.`in`.CounterpartyExposure
import com.openbank.treasury.application.port.`in`.CurrencyPosition
import com.openbank.treasury.application.port.`in`.PositionBasis
import com.openbank.treasury.application.port.`in`.PositionReport
import com.openbank.treasury.application.port.`in`.QuoteBoard
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.LimitNote
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.SimulatedQuote
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
    /** Omit for overnight (next business day). Ignored for CNB_DEPOSIT_FACILITY and CNB_LOMBARD, always overnight. */
    val maturityDate: LocalDate? = null,
    val rationale: String? = null,
    /**
     * ADR-0315 D10: the data an AI agent built this draft from, as a JSON object. Required (with
     * `rationale`) when the caller is an agent; optional for a human dealer.
     */
    val inputs: JsonNode? = null,
    /** FX_SPOT only: the currency the bank buys; exactly one of buy/sell is CZK. */
    val buyCurrency: String? = null,
    /** FX_SPOT only: the currency the bank sells. */
    val sellCurrency: String? = null,
    // ADR-0344 (openapi 1.19.0), all optional and additive.
    val kind: com.openbank.treasury.domain.model.DealKind? = null,
    val bookId: String? = null,
    val calendars: List<String>? = null,
    val businessDayConvention: String? = null,
    val rollDirection: com.openbank.treasury.domain.model.RollDirection? = null,
    val legs: List<LegSpecDto>? = null,
    val securityIsin: String? = null,
    val nominal: BigDecimal? = null,
    val cleanPrice: BigDecimal? = null,
    val yield: BigDecimal? = null,
    val linkedDealIds: List<UUID>? = null,
    val collateral: CollateralDto? = null,
    val regulatory: RegulatoryDto? = null,
    val legalEntityId: String? = null,
    val counterpartyReference: String? = null,
    val channel: DealChannelDto? = null,
    val tradeDateTime: Instant? = null,
    val effectiveDate: LocalDate? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val jurisdiction: String? = null,
    val fixedConvention: Boolean? = null,
    val notionalExchange: Boolean? = null,
    val singleCurrency: Boolean? = null,
    val note: String? = null,
    val settlementAccounts: SettlementAccountsDto? = null,
    val documents: List<DealDocumentDto>? = null,
    val fees: List<DealFeeDto>? = null,
    val linkedDeals: List<LinkedDealDto>? = null,
    val originalDealId: UUID? = null,
    val securityTerms: SecurityTermsDto? = null,
    val fxTerms: FxDealTermsDto? = null,
    val fraTerms: FraTermsDto? = null,
)

data class ReasonRequest(val reason: String? = null)

/** Optional body of `POST /deals/{id}/confirm`: the counterparty's confirmation reference, if any. */
data class ConfirmRequest(val reference: String? = null)

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
            c.currency,
            c.limit,
            c.exposureBefore,
            c.exposureAfter,
            c.headroomAfter,
            c.breached,
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
    /** The limit-check figures when [note] is a limit note; null otherwise. Additive, API 1.16.0. */
    val limitSnapshot: LimitSnapshotResponse? = null,
)

data class LimitSnapshotResponse(
    val limit: BigDecimal,
    val currency: String,
    val exposureAfter: BigDecimal,
    val headroomAfter: BigDecimal,
)

data class LimitOverrideResponse(
    val by: String,
    val reason: String,
    val at: Instant,
    val coversExposureUpTo: BigDecimal,
    val limitAtOverride: BigDecimal,
)

data class JournalRefResponse(val event: String, val idempotencyKey: String, val journalId: UUID, val postedAt: Instant)

/** One posting-rule matrix row; `ifrs9Category` null = every category. */
data class PostingRuleDto(
    val product: ProductType,
    val event: String,
    val ifrs9Category: String?,
    val recognition: String,
    val flowKind: String?,
    val flowDirection: String?,
    val lines: List<PostingRuleLineDto>,
    val version: Int,
) {
    companion object {
        fun from(r: com.openbank.treasury.domain.model.PostingRule) = PostingRuleDto(
            product = r.product,
            event = r.event.name,
            ifrs9Category = r.ifrs9Category?.name,
            recognition = r.recognition.name,
            flowKind = r.selector.kind?.name,
            flowDirection = r.selector.direction?.name,
            lines = r.lines.map { PostingRuleLineDto(it.side.name, it.accountingClass, it.role.name, it.amount.name) },
            version = r.version,
        )
    }
}

data class PostingRuleLineDto(
    val side: String,
    val accountingClass: String,
    val accountingRole: String,
    val amountExpression: String,
)

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
    /** The JSON object an agent's draft was built from (ADR-0315 D10), verbatim; null for a human draft. */
    val inputs: String?,
    val limitCheck: LimitCheckResponse?,
    /** A senior approver's recorded override of a limit breach (ADR-0315 D4); null when none. */
    val limitOverride: LimitOverrideResponse?,
    /** FX_SPOT only (#10896); null for money-market deals. */
    val fx: FxTermsResponse?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val history: List<TransitionResponse>,
    val journals: List<JournalRefResponse>,
    // ADR-0344 (1.19.0)
    val kind: String,
    val bookId: String?,
    val legs: List<LegSpecDto>,
    val scheduleVersion: Int,
    val scheduleHash: String?,
    val version: Int,
    val securityIsin: String?,
    val regulatory: com.openbank.treasury.domain.model.RegulatoryTerms?,
    val linkedDealIds: List<UUID>,
    val collateral: CollateralDto?,
    val legalEntityId: String?,
    val counterpartyReference: String?,
    val channel: String?,
    val tradeDateTime: Instant?,
    val effectiveDate: LocalDate?,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val jurisdiction: String?,
    val calendars: List<String>,
    val businessDayConvention: String?,
    val rollDirection: String,
    val fixedConvention: Boolean,
    val notionalExchange: Boolean?,
    val singleCurrency: Boolean?,
    val note: String?,
    val settlementAccounts: SettlementAccountsDto?,
    val documents: List<DealDocumentDto>,
    val fees: List<DealFeeDto>,
    val linkedDeals: List<LinkedDealDto>,
    val originalDealId: UUID?,
    val securityTerms: SecurityTermsDto?,
    val fxTerms: FxDealTermsDto?,
    val fraTerms: FraTermsDto?,
    val notional: BigDecimal,
    val lastChangedAt: Instant,
    val lastChangedBy: String,
    val counterpartyName: String? = null,
) {
    companion object {
        @Suppress("LongMethod") // a flat field-by-field mapping of the response contract
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
            inputs = d.inputs,
            limitCheck = d.limitCheck?.let(LimitCheckResponse::from),
            limitOverride = d.limitOverride?.let {
                LimitOverrideResponse(it.by.id, it.reason, it.at, it.coversExposureUpTo, it.limitAtOverride)
            },
            fx = FxTermsResponse.from(d),
            createdAt = d.createdAt,
            updatedAt = d.updatedAt,
            history = d.history.map {
                TransitionResponse(
                    it.from,
                    it.to,
                    it.actor.id,
                    it.actor.type.name,
                    it.at,
                    it.note,
                    LimitNote.parse(it.note)?.let { s ->
                        LimitSnapshotResponse(s.limit, s.currency, s.exposureAfter, s.headroomAfter)
                    },
                )
            },
            journals = journals.map { JournalRefResponse(it.event.key, it.idempotencyKey, it.journalId, it.postedAt) },
            kind = d.kind.name,
            bookId = d.bookId,
            legs = d.legs.map(LegSpecDto::from),
            scheduleVersion = d.scheduleVersion,
            scheduleHash = d.scheduleHash,
            version = d.version,
            securityIsin = d.header.securityTerms?.securityIsin,
            regulatory = d.header.regulatory,
            linkedDealIds = d.header.linkedDeals.map { it.dealId },
            collateral = d.header.collateral,
            legalEntityId = d.header.legalEntityId,
            counterpartyReference = d.header.counterpartyReference,
            channel = d.header.channel?.name,
            tradeDateTime = d.header.tradeDateTime,
            effectiveDate = d.header.effectiveDate,
            validFrom = d.header.validFrom,
            validTo = d.header.validTo,
            jurisdiction = d.header.jurisdiction,
            calendars = d.header.calendars,
            businessDayConvention = d.header.businessDayConvention,
            rollDirection = d.header.rollDirection.name,
            fixedConvention = d.header.fixedConvention,
            notionalExchange = d.header.notionalExchange,
            singleCurrency = d.header.singleCurrency,
            note = d.header.note,
            settlementAccounts = d.header.settlementAccounts,
            documents = d.header.documents,
            fees = d.header.fees,
            linkedDeals = d.header.linkedDeals,
            originalDealId = d.header.originalDealId,
            securityTerms = d.header.securityTerms,
            fxTerms = d.header.fxTerms,
            fraTerms = d.header.fraTerms,
            notional = d.legs.firstOrNull()?.notional ?: d.principal,
            lastChangedAt = d.updatedAt,
            lastChangedBy = d.history.lastOrNull()?.actor?.id ?: d.createdBy.id,
        )
    }
}

/** Both legs of an FX spot, named from the bank's side, plus the rate check (#10896). */
data class FxTermsResponse(
    val side: FxSide,
    val buyCurrency: String,
    val buyAmount: BigDecimal,
    val sellCurrency: String,
    val sellAmount: BigDecimal,
    val dealRate: BigDecimal,
    val midRate: BigDecimal?,
    val rateFlag: String?,
) {
    companion object {
        fun from(d: Deal): FxTermsResponse? {
            val fx = d.fx ?: return null
            val buy = fx.side == FxSide.BUY
            return FxTermsResponse(
                side = fx.side,
                buyCurrency = if (buy) d.currency else Deal.CZK,
                buyAmount = if (buy) d.principal else fx.counterAmount,
                sellCurrency = if (buy) Deal.CZK else d.currency,
                sellAmount = if (buy) fx.counterAmount else d.principal,
                dealRate = d.rate,
                midRate = fx.midRate,
                rateFlag = fx.rateFlag,
            )
        }
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
            e.counterparty.id,
            e.counterparty.name,
            e.counterparty.kind,
            e.counterparty.synthetic,
            e.currency,
            e.limit,
            e.exposure,
            e.headroom,
        )
    }
}

/**
 * Read-only per counterparty/currency limit-utilisation line (ADR-0315 D4, #10896). `utilised`
 * and `breached` are computed the exact same way as the booking-time limit check — both derive
 * from [com.openbank.treasury.domain.model.Deal.LIMIT_CONSUMING_STATES] via the same repository
 * query — so this view cannot disagree with what actually blocks booking.
 */
data class LimitUtilisationEntryResponse(
    val counterpartyId: String,
    val name: String,
    val synthetic: Boolean,
    val currency: String,
    val limit: BigDecimal,
    val utilised: BigDecimal,
    val available: BigDecimal,
    val utilisationPercent: BigDecimal,
    val breached: Boolean,
    val activeOverrides: Int,
) {
    companion object {
        fun from(e: CounterpartyExposure) = LimitUtilisationEntryResponse(
            counterpartyId = e.counterparty.id,
            name = e.counterparty.name,
            synthetic = e.counterparty.synthetic,
            currency = e.currency,
            limit = e.limit,
            utilised = e.exposure,
            available = e.headroom,
            utilisationPercent = e.utilisationPercent,
            breached = e.breached,
            activeOverrides = e.activeOverrides,
        )
    }
}

data class LimitUtilisationResponse(val limits: List<LimitUtilisationEntryResponse>)

data class CurrencyPositionResponse(
    val currency: String,
    val placed: BigDecimal,
    val borrowed: BigDecimal,
    val atCnb: BigDecimal,
    val net: BigDecimal,
    val dealCount: Int,
) {
    companion object {
        fun from(p: CurrencyPosition) =
            CurrencyPositionResponse(p.currency, p.placed, p.borrowed, p.atCnb, p.net, p.dealCount)
    }
}

/** [basis] ACTUAL up to [today] (the Europe/Prague accounting day), PROJECTED after it. */
data class PositionsResponse(
    val asOf: LocalDate,
    val today: LocalDate,
    val basis: PositionBasis,
    val countedStates: List<DealState>,
    val positions: List<CurrencyPositionResponse>,
) {
    companion object {
        fun from(r: PositionReport) = PositionsResponse(
            asOf = r.asOf,
            today = r.today,
            basis = r.basis,
            countedStates = r.countedStates.sorted(),
            positions = r.positions.map(CurrencyPositionResponse::from),
        )
    }
}

/**
 * `GET /quotes` (ADR-0315 D9). [synthetic] is ALWAYS true, on the board and on every quote: these
 * are invented counterparties pricing off the risk engine's curve, never a dealable market price.
 */
data class QuoteBoardResponse(
    val product: ProductType,
    val currency: String,
    val tenorDays: Int,
    val synthetic: Boolean,
    val quotes: List<QuoteResponse>,
) {
    companion object {
        fun from(board: QuoteBoard) = QuoteBoardResponse(
            product = board.product,
            currency = board.currency,
            tenorDays = board.tenorDays,
            synthetic = true,
            quotes = board.quotes.map { QuoteResponse.from(it, board.product) },
        )
    }
}

data class QuoteResponse(
    val counterpartyId: String,
    val synthetic: Boolean,
    val bid: BigDecimal,
    val ask: BigDecimal,
    /** The side a deal of the requested product is struck at: bid for a placement, ask for a borrowing. */
    val dealRate: BigDecimal,
    val mid: BigDecimal,
    val spreadBp: Int,
    val curveSetId: UUID,
    val curveAsOf: LocalDate,
    val curveIndex: String,
    /** The risk engine's label for the curve's market data (`synthetic` / `production`). */
    val curveProvenance: String,
) {
    companion object {
        fun from(q: SimulatedQuote, product: ProductType) = QuoteResponse(
            counterpartyId = q.counterpartyId,
            synthetic = q.synthetic,
            bid = q.bid,
            ask = q.ask,
            dealRate = q.rateFor(product),
            mid = q.mid,
            spreadBp = q.spreadBp,
            curveSetId = q.curveSetId,
            curveAsOf = q.curveAsOf,
            curveIndex = q.curveIndex,
            curveProvenance = q.curveProvenance,
        )
    }
}
