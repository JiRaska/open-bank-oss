// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.treasury.domain.cashflow.RateSourceWire
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The producer's own claim of who emitted the event — audit-service records it verbatim rather
 * than deriving attribution from the topic name (#5256/#6035).
 */
private const val SOURCE_SERVICE = "treasury-service"

/**
 * One data class per event type: the ADR-0006 contract-agreement gate pairs each AsyncAPI message
 * in `openbank-contracts/openbank-treasury-service/asyncapi.yaml` with the class whose companion
 * declares that `EVENT_TYPE` and compares the constructor properties. Add an event here AND there.
 */
data class DealBooked(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val rate: BigDecimal,
    val valueDate: LocalDate,
    val maturityDate: LocalDate,
    val createdBy: String,
    val approvedBy: String,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
    /** FX_SPOT only (#10896): the bank's side on the foreign `currency`; null for money-market deals. */
    val fxSide: FxSide? = null,
    /** FX_SPOT only: the CZK leg (`principal` x `rate`); null for money-market deals. */
    val counterAmount: BigDecimal? = null,
    /**
     * ADR-0315 D4: the product limit the booking was evaluated against (a booked deal is always
     * within it). Additive and optional, so `v1` consumers are unaffected.
     */
    val productLimit: ProductLimitApplied? = null,
    /** ADR-0344 D2 (additive): ACTUAL books and pays; PLANNED is booked for the forecast only. Absent = ACTUAL. */
    val kind: DealKind = DealKind.ACTUAL,
    val bookId: String? = null,
    val legs: List<LegSpec> = emptyList(),
    val scheduleVersion: Int = 0,
    val scheduleHash: String? = null,
    /**
     * The deal's roll direction (additive, asyncapi 1.7.0). A leg published with stub `NONE` is
     * placed by it (`StubType.resolve`), so a consumer derives the schedule treasury stored. Absent
     * on older events = FROM_END, the deal default.
     */
    val rollDirection: RollDirection = RollDirection.FROM_END,
    /**
     * The deal header's calendar codes (additive, asyncapi 1.7.0): the joint calendar a leg without
     * its own `paymentCalendars` is generated on. Empty = the leg currency's calendar.
     */
    val calendars: List<String> = emptyList(),
    /** The deal header's business-day convention (additive, 1.7.0); null = the product default. */
    val businessDayConvention: String? = null,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.booked.v1"
    }
}

/**
 * ADR-0315 D2: the counterparty's confirmation of the booked terms was received. Nothing has
 * posted. [simulated] is true when the in-process simulated counterparty set (ADR-0315 D9,
 * sandbox only) confirmed it rather than a back-office person — a SYNTHETIC confirmation.
 */
data class DealConfirmed(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val valueDate: LocalDate,
    val confirmedBy: String,
    val simulated: Boolean,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
    /** ADR-0344 D2 (additive): absent = ACTUAL. */
    val kind: DealKind = DealKind.ACTUAL,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.confirmed.v1"
    }
}

data class DealSettled(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val valueDate: LocalDate,
    val maturityDate: LocalDate,
    val ledgerJournalId: UUID,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
    /** FX_SPOT only (#10896): the bank's side on the foreign `currency`; null for money-market deals. */
    val fxSide: FxSide? = null,
    /** FX_SPOT only: the CZK leg (`principal` x `rate`); null for money-market deals. */
    val counterAmount: BigDecimal? = null,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.settled.v1"
    }
}

data class DealMatured(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val interest: BigDecimal,
    val maturityDate: LocalDate,
    val ledgerJournalId: UUID,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.matured.v1"
    }
}

data class DealReversed(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val reversedBy: String,
    val reason: String,
    /** Null when the deal was reversed from BOOKED or CONFIRMED, before anything had posted. */
    val ledgerJournalId: UUID?,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
    /** FX_SPOT only (#10896): the bank's side on the foreign `currency`; null for money-market deals. */
    val fxSide: FxSide? = null,
    /** FX_SPOT only: the CZK leg (`principal` x `rate`); null for money-market deals. */
    val counterAmount: BigDecimal? = null,
    /** ADR-0344 D2 (additive): absent = ACTUAL. */
    val kind: DealKind = DealKind.ACTUAL,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.reversed.v1"
    }
}

/**
 * ADR-0315 D7: an OPEN nostro reconciliation break reached the alert threshold (age in business
 * days AND amount). Emitted once per break. Carries no reference or narrative — a statement line's
 * remittance text can name a customer, and this topic is read by audit-service and the risk engine.
 */
data class NostroBreakAged(
    val breakId: UUID,
    val iban: String,
    val glCode: String,
    val currency: String,
    val side: BreakSide,
    val ourSide: Side,
    val amount: BigDecimal,
    val bookingDate: LocalDate,
    val firstSeenOn: LocalDate,
    val ageBusinessDays: Int,
    val thresholdDays: Int,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.nostro.break-aged.v1"
    }
}

/** The product-limit decision recorded on [DealBooked]: the mandate in the deal's currency, and that it held. */
data class ProductLimitApplied(val decision: String, val maxPrincipal: BigDecimal?, val maxTenorDays: Long?) {
    companion object {
        const val WITHIN = "WITHIN_LIMIT"

        fun of(limit: ProductLimit, currency: String) =
            ProductLimitApplied(WITHIN, limit.maxPrincipal[currency], limit.maxTenorDays)
    }
}

/**
 * ADR-0344 D4: a cash-flow ledger version was generated (at submission). Carries counts and the
 * hash, not the rows: consumers read the rows through the API by `scheduleVersion`.
 */
data class CashFlowGenerated(
    val dealId: UUID,
    val product: ProductType,
    val kind: DealKind,
    val bookId: String?,
    val dealVersion: Int,
    val scheduleVersion: Int,
    val scheduleHash: String,
    val flowCount: Int,
    val projectedCount: Int,
    val carriedOverrides: Int,
    val conflicts: Int,
    val occurredAt: Instant,
    /**
     * The stored rows themselves (asyncapi 1.7.0). The risk engine keeps them as the deal's facts;
     * before 1.7.0 they were absent and every event was acked by it as malformed.
     */
    val flows: List<LedgerFlowItem> = emptyList(),
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.cashflow.generated.v1"
    }
}

/** One stored cash-flow row as [CashFlowGenerated] carries it (contract `CashFlow`, ADR-0344 D4). */
data class LedgerFlowItem(
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
) {
    companion object {
        fun of(f: com.openbank.treasury.domain.cashflow.CashFlow) = LedgerFlowItem(
            f.flowId, f.legIndex, f.periodIndex, f.kind.name, f.direction.name, f.currency, f.accrualStart,
            f.accrualEnd, f.fixingDate, f.paymentDate, f.notional, f.rate,
            RateSourceWire.encode(
                f.rateSource,
            ),
            f.amount,
            f.state.name, f.manual,
        )
    }
}

/** One flow fixed by [CashFlowFixed]: its slot, the observed rate and the amount it now pays. */
data class FixedFlowItem(
    val flowId: UUID,
    val legIndex: Int,
    val periodIndex: Int,
    val kind: String,
    val direction: String,
    val currency: String,
    val accrualStart: LocalDate?,
    val accrualEnd: LocalDate?,
    val paymentDate: LocalDate,
    val rate: BigDecimal,
    val amount: BigDecimal,
    val rateSource: String,
    val state: String,
)

/**
 * ADR-0344 D5: floating flows of one deal moved PROJECTED/MISSING -> FIXED from an observed index
 * value that passed the quality gate. [sha256] is the provenance hash of (index, date, value, source).
 */
data class CashFlowFixed(
    val dealId: UUID,
    val dealVersion: Int,
    val scheduleVersion: Int,
    val index: String,
    val fixingDate: LocalDate,
    val value: BigDecimal,
    val source: String,
    val sha256: String,
    val flows: List<FixedFlowItem>,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.cashflow.fixed.v1"
    }
}

/** ADR-0344 D4: a four-eyes cash-flow override was approved or rejected. */
data class CashFlowOverridden(
    val dealId: UUID,
    val flowId: UUID,
    val overrideId: UUID,
    val status: String,
    val paymentDate: LocalDate?,
    val amount: BigDecimal?,
    val originalPaymentDate: LocalDate?,
    val originalAmount: BigDecimal?,
    val reason: String,
    val proposedBy: String,
    val decidedBy: String,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.cashflow.overridden.v1"
    }
}

/**
 * ADR-0344 D7: a four-eyes correction of a booked deal was approved. The new terms version and its
 * cash-flow ledger committed in this transaction; [deltaJournals] are the journals it owes the
 * ledger (posted after the commit, idempotent on their keys). [fullUnwind]: the deal ended REVERSED.
 */
data class DealCorrected(
    val dealId: UUID,
    val correctionId: UUID,
    val product: ProductType,
    val fromVersion: Int,
    val toVersion: Int,
    val scheduleVersion: Int,
    val fullUnwind: Boolean,
    val changedFlows: Int,
    val deltaJournals: Int,
    val effectiveFrom: LocalDate,
    val reason: String,
    val proposedBy: String,
    val approvedBy: String,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.corrected.v1"
    }
}
