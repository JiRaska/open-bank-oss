// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.finance.schedule.Frequency
import com.openbank.treasury.application.port.`in`.CashFlowLedgerView
import com.openbank.treasury.application.port.out.DealVersionView
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowConflict
import com.openbank.treasury.domain.cashflow.CashFlowOverride
import com.openbank.treasury.domain.cashflow.RateSourceWire
import com.openbank.treasury.domain.model.Amortisation
import com.openbank.treasury.domain.model.AmortisationStep
import com.openbank.treasury.domain.model.AmortisationType
import com.openbank.treasury.domain.model.CapFloorBasis
import com.openbank.treasury.domain.model.CollateralTerms
import com.openbank.treasury.domain.model.DayCountConvention
import com.openbank.treasury.domain.model.DealChannel
import com.openbank.treasury.domain.model.DealDocument
import com.openbank.treasury.domain.model.DealFee
import com.openbank.treasury.domain.model.DealHeader
import com.openbank.treasury.domain.model.FraTerms
import com.openbank.treasury.domain.model.FxDealTerms
import com.openbank.treasury.domain.model.LegRate
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.LinkRelation
import com.openbank.treasury.domain.model.LinkedDeal
import com.openbank.treasury.domain.model.PayReceive
import com.openbank.treasury.domain.model.RateType
import com.openbank.treasury.domain.model.RegulatoryTerms
import com.openbank.treasury.domain.model.RollDirection
import com.openbank.treasury.domain.model.SecurityTerms
import com.openbank.treasury.domain.model.SettlementAccounts
import com.openbank.treasury.domain.model.StubType
import com.openbank.treasury.domain.model.TradeReporting
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Wire form of a leg's rate (rates in annual percent; `type` FIXED | FLOATING | NONE). */
data class LegRateDto(
    val type: RateType? = null,
    val rate: BigDecimal? = null,
    val index: String? = null,
    val spread: BigDecimal? = null,
    val fixingLagBusinessDays: Int? = null,
    val resetFrequency: Frequency? = null,
    val cap: BigDecimal? = null,
    val floor: BigDecimal? = null,
    val capFloorAppliesTo: CapFloorBasis? = null,
    val rateUntilFirstFixing: BigDecimal? = null,
) {
    fun toDomain() = LegRate(
        requireNotNull(type) { "leg rate.type is required" },
        rate,
        index,
        spread,
        fixingLagBusinessDays,
        resetFrequency,
        cap,
        floor,
        capFloorAppliesTo,
        rateUntilFirstFixing,
    )

    companion object {
        fun from(r: LegRate) = LegRateDto(
            r.type, r.rate, r.index, r.spread, r.fixingLagBusinessDays, r.resetFrequency, r.cap, r.floor,
            r.capFloorAppliesTo, r.rateUntilFirstFixing,
        )
    }
}

data class AmortisationDto(val type: AmortisationType? = null, val schedule: List<AmortisationStep>? = null)

/** Wire form of a [LegSpec]; `dayCount` uses the market names (ACT/360, 30E/360, ...). */
data class LegSpecDto(
    val payReceive: PayReceive? = null,
    val currency: String? = null,
    val notional: BigDecimal? = null,
    val amortisation: AmortisationDto? = null,
    val amortisationStart: LocalDate? = null,
    val accrualStart: LocalDate? = null,
    val accrualEnd: LocalDate? = null,
    val accrualFrequency: Frequency? = null,
    val paymentFrequency: Frequency? = null,
    val stub: StubType? = null,
    val adjustPaymentDate: Boolean? = null,
    val adjustAccrualPeriod: Boolean? = null,
    val paymentLagBusinessDays: Int? = null,
    val paymentCalendars: List<String>? = null,
    val dayCount: String? = null,
    val rate: LegRateDto? = null,
    val initialNotionalExchange: Boolean? = null,
    val finalNotionalExchange: Boolean? = null,
    val endOfMonth: Boolean? = null,
) {
    fun toDomain(): LegSpec = LegSpec(
        payReceive = requireNotNull(payReceive) { "leg payReceive is required" },
        currency = requireNotNull(currency) { "leg currency is required" },
        notional = requireNotNull(notional) { "leg notional is required" },
        accrualStart = requireNotNull(accrualStart) { "leg accrualStart is required" },
        accrualEnd = requireNotNull(accrualEnd) { "leg accrualEnd is required" },
        dayCount = DayCountConvention.fromWire(requireNotNull(dayCount) { "leg dayCount is required" }),
        rate = requireNotNull(rate) { "leg rate is required" }.toDomain(),
        amortisation = amortisation?.let {
            Amortisation(it.type ?: AmortisationType.BULLET, it.schedule.orEmpty())
        } ?: Amortisation.BULLET,
        accrualFrequency = accrualFrequency ?: Frequency.Zero,
        paymentFrequency = paymentFrequency ?: accrualFrequency ?: Frequency.Zero,
        stub = stub,
        adjustPaymentDate = adjustPaymentDate ?: true,
        adjustAccrualPeriod = adjustAccrualPeriod ?: false,
        paymentLagBusinessDays = paymentLagBusinessDays ?: 0,
        initialNotionalExchange = initialNotionalExchange ?: false,
        finalNotionalExchange = finalNotionalExchange ?: false,
        amortisationStart = amortisationStart,
        paymentCalendars = paymentCalendars.orEmpty(),
        endOfMonth = endOfMonth ?: false,
    )

    companion object {
        fun from(l: LegSpec) = LegSpecDto(
            payReceive = l.payReceive,
            currency = l.currency,
            notional = l.notional,
            amortisation = AmortisationDto(l.amortisation.type, l.amortisation.schedule),
            amortisationStart = l.amortisationStart,
            accrualStart = l.accrualStart,
            accrualEnd = l.accrualEnd,
            accrualFrequency = l.accrualFrequency,
            paymentFrequency = l.paymentFrequency,
            stub = l.stub,
            adjustPaymentDate = l.adjustPaymentDate,
            adjustAccrualPeriod = l.adjustAccrualPeriod,
            paymentLagBusinessDays = l.paymentLagBusinessDays,
            paymentCalendars = l.paymentCalendars,
            dayCount = l.dayCount.wire,
            rate = LegRateDto.from(l.rate),
            initialNotionalExchange = l.initialNotionalExchange,
            finalNotionalExchange = l.finalNotionalExchange,
            endOfMonth = l.endOfMonth,
        )
    }
}

data class TradeReportingDto(
    val uti: String? = null,
    val counterpartyLei: String? = null,
    val reportingCounterpartyLei: String? = null,
    val clearingObligation: Boolean? = null,
    val collateralisation: com.openbank.treasury.domain.model.Collateralisation? = null,
)

data class RegulatoryDto(
    val tradeReporting: TradeReportingDto? = null,
    /** Deprecated alias of [tradeReporting] from the first 1.19.0 drafts. */
    val emir: TradeReportingDto? = null,
    val centralBankCodes: List<String>? = null,
    val cnbCodes: List<String>? = null,
    val lcrCategory: String? = null,
    val nsfrCategory: String? = null,
) {
    fun toDomain(): RegulatoryTerms {
        val tr = tradeReporting ?: emir
        return RegulatoryTerms(
            tradeReporting = tr?.let {
                TradeReporting(
                    it.uti,
                    it.counterpartyLei ?: it.reportingCounterpartyLei,
                    it.clearingObligation,
                    it.collateralisation,
                )
            },
            centralBankCodes = (centralBankCodes ?: cnbCodes).orEmpty(),
            lcrCategory = lcrCategory,
            nsfrCategory = nsfrCategory,
        )
    }
}

/** The ADR-0344 header fields of the draft request, all optional. */
@Suppress("LongParameterList")
fun headerOf(r: DraftDealRequest): DealHeader = DealHeader(
    legalEntityId = r.legalEntityId,
    counterpartyReference = r.counterpartyReference,
    channel = r.channel,
    tradeDateTime = r.tradeDateTime,
    effectiveDate = r.effectiveDate,
    validFrom = r.validFrom,
    validTo = r.validTo,
    jurisdiction = r.jurisdiction,
    calendars = r.calendars.orEmpty(),
    businessDayConvention = r.businessDayConvention,
    rollDirection = r.rollDirection ?: RollDirection.FROM_END,
    fixedConvention = r.fixedConvention ?: false,
    notionalExchange = r.notionalExchange,
    singleCurrency = r.singleCurrency,
    note = r.note,
    settlementAccounts = r.settlementAccounts,
    documents = r.documents.orEmpty(),
    fees = r.fees.orEmpty(),
    linkedDeals = r.linkedDeals.orEmpty() + r.linkedDealIds.orEmpty()
        .filter { id -> r.linkedDeals.orEmpty().none { it.dealId == id } }
        .map { LinkedDeal(it, LinkRelation.HEDGE) },
    originalDealId = r.originalDealId,
    regulatory = r.regulatory?.toDomain(),
    collateral = r.collateral,
    securityTerms = r.securityTerms ?: r.securityIsin?.let {
        SecurityTerms(it, requireNotNull(r.nominal) { "nominal is required with securityIsin" }, r.cleanPrice, r.yield)
    },
    fxTerms = r.fxTerms,
    fraTerms = r.fraTerms,
    depositLoanTerms = r.depositLoanTerms,
    bookId = r.bookId,
)

data class CashFlowDto(
    val flowId: UUID,
    val legIndex: Int,
    val periodIndex: Int,
    val kind: String,
    val direction: String,
    val currency: String,
    val accrualStart: LocalDate?,
    val accrualEnd: LocalDate?,
    val fixingDate: LocalDate?,
    val paymentDate: LocalDate,
    val notional: BigDecimal?,
    val rate: BigDecimal?,
    val rateSource: String,
    val amount: BigDecimal?,
    val state: String,
    val manual: Boolean,
    val original: Map<String, Any?>?,
    val override: Map<String, Any?>?,
    val paymentId: String?,
    val journalRefs: List<String>,
) {
    companion object {
        fun from(f: CashFlow, overrides: List<CashFlowOverride>) = CashFlowDto(
            flowId = f.flowId,
            legIndex = f.legIndex,
            periodIndex = f.periodIndex,
            kind = f.kind.name,
            direction = f.direction.name,
            currency = f.currency,
            accrualStart = f.accrualStart,
            accrualEnd = f.accrualEnd,
            fixingDate = f.fixingDate,
            paymentDate = f.paymentDate,
            notional = f.notional,
            rate = f.rate,
            rateSource = RateSourceWire.encode(f.rateSource),
            amount = f.amount,
            state = f.state.name,
            manual = f.manual,
            original = if (f.manual) {
                mapOf(
                    "paymentDate" to f.originalPaymentDate,
                    "amount" to f.originalAmount,
                )
            } else {
                null
            },
            override = f.overrideId?.let { id ->
                overrides.firstOrNull { it.overrideId == id }?.let {
                    mapOf(
                        "overrideId" to it.overrideId,
                        "reason" to it.reason,
                        "by" to it.proposedBy.id,
                        "approvedBy" to it.decidedBy?.id,
                    )
                }
            },
            paymentId = null,
            journalRefs = emptyList(),
        )
    }
}

data class CashFlowLedgerDto(
    val dealId: UUID,
    val dealVersion: Int,
    val scheduleVersion: Int,
    val scheduleHash: String?,
    val generatedAt: Instant?,
    val flows: List<CashFlowDto>,
) {
    companion object {
        fun from(v: CashFlowLedgerView) = CashFlowLedgerDto(
            v.dealId,
            v.dealVersion,
            v.scheduleVersion,
            v.scheduleHash,
            v.generatedAt,
            v.flows.map { CashFlowDto.from(it, v.overrides) },
        )
    }
}

data class CashFlowOverrideRequest(
    val paymentDate: LocalDate? = null,
    val amount: BigDecimal? = null,
    val reason: String? = null,
)

data class CashFlowOverrideDto(
    val overrideId: UUID,
    val dealId: UUID,
    val flowId: UUID,
    val status: String,
    val paymentDate: LocalDate?,
    val amount: BigDecimal?,
    val reason: String,
    val proposedBy: String,
    val proposedByType: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
) {
    companion object {
        fun from(o: CashFlowOverride) = CashFlowOverrideDto(
            o.overrideId, o.dealId, o.flowId, o.status.name, o.paymentDate, o.amount, o.reason, o.proposedBy.id,
            o.proposedBy.type.name, o.proposedAt, o.decidedBy?.id, o.decidedAt, o.decisionReason,
        )
    }
}

data class CashFlowConflictDto(
    val conflictId: UUID,
    val dealId: UUID,
    val overrideId: UUID,
    val legIndex: Int,
    val periodIndex: Int,
    val fromScheduleVersion: Int,
    val toScheduleVersion: Int,
    val detectedAt: Instant,
    val reason: String,
) {
    companion object {
        fun from(c: CashFlowConflict) = CashFlowConflictDto(
            c.conflictId, c.dealId, c.overrideId, c.slot.legIndex, c.slot.periodIndex, c.fromScheduleVersion,
            c.toScheduleVersion, c.detectedAt, c.reason,
        )
    }
}

data class DealVersionDto(
    val dealId: UUID,
    val version: Int,
    val scheduleVersion: Int?,
    val scheduleHash: String?,
    val createdBy: String,
    val createdAt: Instant,
    val changes: List<String>,
) {
    companion object {
        fun from(v: DealVersionView) =
            DealVersionDto(v.dealId, v.version, v.scheduleVersion, v.scheduleHash, v.createdBy, v.createdAt, v.changes)
    }
}

/** Typealiases keep the request shape readable where the domain types already fit the wire. */
typealias SettlementAccountsDto = SettlementAccounts
typealias DealDocumentDto = DealDocument
typealias DealFeeDto = DealFee
typealias LinkedDealDto = LinkedDeal
typealias CollateralDto = CollateralTerms
typealias SecurityTermsDto = SecurityTerms
typealias FxDealTermsDto = FxDealTerms
typealias FraTermsDto = FraTerms
typealias DealChannelDto = DealChannel
