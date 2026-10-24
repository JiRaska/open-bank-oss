// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.`in`

import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.treasury.application.port.out.CompletePortfolio
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.StoredPortfolioStatement
import com.openbank.treasury.application.port.out.StoredStatement
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.BreakAlertPolicy
import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CustodyStatement
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealHeader
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.NostroReconciliation
import com.openbank.treasury.domain.model.NostroStatement
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.SimulatedQuote
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferencePayload
import com.openbank.treasury.domain.reference.ReferenceRecord
import com.openbank.treasury.domain.reference.ReferenceStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class DraftDealCommand(
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val rate: BigDecimal,
    val tradeDate: LocalDate?,
    /** Required, except for FX_SPOT where it defaults to T+2 business days after the trade date. */
    val valueDate: LocalDate?,
    val maturityDate: LocalDate?,
    val rationale: String?,
    /** ADR-0315 D10: the JSON object of data an agent's draft was built from. */
    val inputs: String? = null,
    /** FX_SPOT only: the bank's side on the foreign [currency]. */
    val fxSide: FxSide? = null,
    /** ADR-0344 D2. */
    val kind: DealKind = DealKind.ACTUAL,
    /** Required for the leg-described products; derived from the flat fields for the legacy five. */
    val legs: List<LegSpec> = emptyList(),
    val header: DealHeader = DealHeader.EMPTY,
)

data class DealView(val deal: Deal, val journals: List<LedgerJournalRef>)

/**
 * Per counterparty/currency limit line, doubling as the treasury limit-utilisation view (#10896).
 * [exposure] MUST be computed the exact same way the booking-time [com.openbank.treasury.domain.model.LimitCheck]
 * is — both ultimately read [DealRepository.exposure], which itself derives its state/product
 * filter from [com.openbank.treasury.domain.model.Deal.LIMIT_CONSUMING_STATES] — so this view and
 * the check that actually blocks booking cannot silently disagree.
 */
data class CounterpartyExposure(
    val counterparty: Counterparty,
    val currency: String,
    val limit: BigDecimal,
    val exposure: BigDecimal,
    /** Deals PENDING_APPROVAL right now whose senior limit override is still in force (ADR-0315 D4). */
    val activeOverrides: Int = 0,
) {
    val headroom: BigDecimal get() = limit - exposure
    val breached: Boolean get() = exposure > limit

    /** 0 when the limit itself is zero (nothing to utilise), never a divide-by-zero. */
    val utilisationPercent: BigDecimal
        get() = if (limit.signum() == 0) {
            BigDecimal.ZERO
        } else {
            RoundingPolicy.RATIO_PERCENT.divide(exposure.multiply(HUNDRED), limit)
        }

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal(100)
    }
}

/** Daily position per currency: outstanding principal on a date, and how many deals make it up. */
data class CurrencyPosition(
    val currency: String,
    val placed: BigDecimal,
    val borrowed: BigDecimal,
    val atCnb: BigDecimal,
    val dealCount: Int = 0,
) {
    val net: BigDecimal get() = placed + atCnb - borrowed
}

/**
 * ACTUAL: the date is the bank's accounting day today or earlier, and only deals whose cash has
 * moved (SETTLED, or since MATURED) count. PROJECTED: the date is after today, and deals already
 * concluded but not yet settled (BOOKED, CONFIRMED) count too, on their contracted value and
 * maturity dates. A projection holds no new deals.
 */
enum class PositionBasis { ACTUAL, PROJECTED }

/** The daily position as of [asOf], computed against the accounting day [today] (Europe/Prague). */
data class PositionReport(
    val asOf: LocalDate,
    val today: LocalDate,
    val basis: PositionBasis,
    val countedStates: Set<DealState>,
    val positions: List<CurrencyPosition>,
)

/**
 * One simulated-market pass. [declined] counts BOOKED deals a simulated counterparty did NOT
 * confirm because they were struck off its quote (ADR-0315 D9) — a counterparty decision, not a
 * failure: the deal waits for a person to confirm or reverse it.
 */
data class SimulatedMarketRun(val moved: Int, val failures: List<Throwable>, val declined: Int = 0)

/** The simulated counterparties' quotes for one product, currency and tenor, off one curve set. */
data class QuoteBoard(
    val product: ProductType,
    val currency: String,
    val tenorDays: Int,
    val quotes: List<SimulatedQuote>,
)

/** ADR-0315 D9: SYNTHETIC two-way quotes of the simulated counterparty set. */
fun interface TreasuryQuoteUseCase {
    suspend fun quotes(product: ProductType, currency: String, tenorDays: Int): QuoteBoard
}

/** One daily-accrual pass (ADR-0315 D5): journals posted and per-deal failures, none swallowed. */
data class AccrualRun(val journals: Int, val failures: List<Throwable>)

@Suppress("TooManyFunctions")
interface TreasuryDealUseCase {
    /**
     * ADR-0344 D7: the SAME term vetting a draft, submission and booking run, applied to the deal a
     * correction would make — reference effectiveness, the FX rate flag, the product mandate (never
     * overridable) and the counterparty limit on the corrected exposure (a breach passes only under
     * [override], a senior's recorded override that still covers it). Returns the corrected deal as
     * vetted and its limit check (null for a product that consumes no limit).
     */
    suspend fun vetCorrection(
        target: com.openbank.treasury.domain.model.Deal,
        override: com.openbank.treasury.domain.model.LimitOverride?,
    ): Pair<com.openbank.treasury.domain.model.Deal, com.openbank.treasury.domain.model.LimitCheck?>

    /**
     * Every command takes the client's idempotency key (null only for the in-process simulated
     * market). A replayed key returns the deal as it now stands; a key reused for a different
     * command or deal is refused (400).
     */
    suspend fun draft(command: DraftDealCommand, actor: Actor, key: String? = null): Deal

    /** ADR-0344: new terms for a DRAFT (`PUT /deals/{id}`); a human, or the agent that drafted it. */
    suspend fun editDraft(dealId: UUID, command: DraftDealCommand, actor: Actor, key: String? = null): Deal
    suspend fun submit(dealId: UUID, actor: Actor, key: String? = null): Deal
    suspend fun approve(dealId: UUID, actor: Actor, key: String? = null): Deal

    /** ADR-0315 D4: a senior approver records an override of a limit breach, with a reason. */
    suspend fun overrideLimit(dealId: UUID, reason: String, actor: Actor, key: String? = null): Deal
    suspend fun reject(dealId: UUID, reason: String, actor: Actor, key: String? = null): Deal
    suspend fun cancel(dealId: UUID, actor: Actor, key: String? = null): Deal

    /** ADR-0344 catalogue: the bank's own confirmation was sent (state unchanged, posts nothing). */
    suspend fun sendConfirmation(
        dealId: UUID,
        documentId: String?,
        channel: String?,
        actor: Actor,
        key: String? = null,
    ): Deal

    /** ADR-0315 D2: the counterparty confirmed the booked terms (BOOKED -> CONFIRMED). Posts nothing. */
    suspend fun confirm(dealId: UUID, reference: String?, actor: Actor, key: String? = null): Deal
    suspend fun settle(dealId: UUID, actor: Actor, key: String? = null): Deal
    suspend fun mature(dealId: UUID, actor: Actor, key: String? = null): Deal
    suspend fun reverse(dealId: UUID, reason: String, actor: Actor, key: String? = null): Deal
    suspend fun get(dealId: UUID): DealView
    suspend fun list(state: DealState?): List<Deal>
    suspend fun counterparties(): List<CounterpartyExposure>

    /** The daily position on [asOf] (default: the accounting day today, Europe/Prague). */
    suspend fun positions(asOf: LocalDate?): PositionReport

    /** One pass of the simulated market (ADR-0315 D9): confirm every BOOKED deal, settle and mature everything due. */
    suspend fun runSimulatedMarket(): SimulatedMarketRun

    /** Post every missing daily accrual of every SETTLED deal up to [asOf] (capped at maturity). */
    suspend fun accrueInterest(asOf: LocalDate): AccrualRun

    /**
     * ADR-0344 D7 outflow: post, through the posting matrix, the accruals and the PAYMENT journals of
     * every flow of [dealId] paid on or before [through] (a SENT/SETTLED payment's value date). The
     * keys are the same `treasury:<dealId>:payment:<flowId>` the daily run uses, so whichever posts
     * first wins and the other is a ledger replay. A legacy product or a deal not SETTLED posts
     * nothing. Returns the journals posted.
     */
    suspend fun postOutflow(dealId: java.util.UUID, through: LocalDate): Int = 0

    /** ADR-0344 D7: the posting-rule matrix, every product (versioned data). */
    fun postingRules(): List<com.openbank.treasury.domain.model.PostingRule> =
        com.openbank.treasury.domain.model.PostingMatrix.rules
}

interface NostroReconciliationUseCase {
    /**
     * Store a parsed correspondent statement for a CONFIGURED nostro account. A replay of
     * [idempotencyKey] with the same bytes returns the original; with different bytes it is a
     * conflict. The same (IBAN, statement id) under a new key is a conflict too.
     */
    suspend fun upload(
        statement: NostroStatement,
        sha256: String,
        idempotencyKey: String,
        actor: Actor,
    ): StoredStatement

    /** Compare the statement with the ledger's nostro GL for the statement date. Posts nothing. */
    suspend fun reconcile(statementId: UUID): NostroReconciliation
}

/** A break with its age evaluated for a given day (ADR-0315 D7). */
data class NostroBreakView(val brk: NostroBreak, val ageBusinessDays: Int, val aged: Boolean)

/** The outcome of one sweep: statements observed (and failed), open and aged breaks, alerts written. */
data class NostroBreakSweep(
    val observed: Int,
    val failures: List<Throwable>,
    val open: Int,
    val aged: Int,
    val alerted: Int,
)

interface NostroBreakUseCase {
    /** Reconcile [statementId] and record what it leaves unmatched as breaks; resolves what now matches. */
    suspend fun observe(statementId: UUID): BreakChanges

    /** Observe every recent statement, then alert (once each) on open breaks over the threshold. */
    suspend fun sweep(today: LocalDate): NostroBreakSweep

    /** Breaks of a CONFIGURED nostro [iban], oldest first. @throws NostroAccountNotFoundException */
    suspend fun breaks(iban: String, includeResolved: Boolean): List<NostroBreakView>

    val policy: BreakAlertPolicy
}

data class ProposeReferenceCommand(
    val kind: ReferenceKind,
    val key: String,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val payload: ReferencePayload,
    val reason: String,
)

data class ReferenceHistory(
    val kind: ReferenceKind,
    val key: String,
    val active: ReferenceRecord?,
    val versions: List<ReferenceRecord>,
)

/** `type` is `deal` or a reference kind's wire name; `field` names what points at the record. */
data class ReferenceDependant(val type: String, val id: String, val field: String)

interface ReferenceDataUseCase {
    suspend fun list(kind: ReferenceKind, status: ReferenceStatus?, asOf: LocalDate?): List<ReferenceRecord>

    suspend fun history(kind: ReferenceKind, key: String): ReferenceHistory

    suspend fun propose(command: ProposeReferenceCommand, actor: Actor, key: String): ReferenceRecord

    /** [seniorApprover]: the caller holds the senior approver role (needed to raise a counterparty limit). */
    suspend fun approve(
        kind: ReferenceKind,
        recordKey: String,
        version: Int,
        actor: Actor,
        key: String,
        seniorApprover: Boolean,
    ): ReferenceRecord

    suspend fun reject(
        kind: ReferenceKind,
        recordKey: String,
        version: Int,
        reason: String,
        actor: Actor,
        key: String,
    ): ReferenceRecord

    suspend fun verify(kind: ReferenceKind, recordKey: String, version: Int, actor: Actor, key: String): ReferenceRecord

    suspend fun dependencies(kind: ReferenceKind, key: String): List<ReferenceDependant>

    /** Many PENDING proposals of one kind, all-or-nothing, under one Idempotency-Key. */
    suspend fun importProposals(
        kind: ReferenceKind,
        commands: List<ProposeReferenceCommand>,
        actor: Actor,
        key: String,
    ): List<ReferenceRecord>

    /** Who proposed, approved, rejected or verified what, newest first. */
    suspend fun activity(since: java.time.Instant?): List<ReferenceActivity>
}

data class ReferenceActivity(
    val at: java.time.Instant,
    val actor: String,
    val action: String,
    val kind: String,
    val key: String,
    val version: Int,
)

/** The stored ledger of one schedule version (ADR-0344 D4). */
data class CashFlowLedgerView(
    val dealId: UUID,
    val dealVersion: Int,
    val scheduleVersion: Int,
    val scheduleHash: String?,
    val generatedAt: java.time.Instant?,
    val flows: List<com.openbank.treasury.domain.cashflow.CashFlow>,
    val overrides: List<com.openbank.treasury.domain.cashflow.CashFlowOverride>,
)

enum class TreasuryEventType {
    REFIXING,
    INTEREST_PAYMENT,
    PRINCIPAL_PAYMENT,
    MATURITY,
    OPTION_EXERCISE,
    MARGIN_CALL,
    SETTLEMENT,
}

data class TreasuryCalendarEvent(
    val date: LocalDate,
    val type: TreasuryEventType,
    val description: String,
    val dealId: UUID,
    val flowId: UUID?,
    val responsibleRole: String,
)

interface CashFlowUseCase {
    suspend fun ledger(dealId: UUID, scheduleVersion: Int?): CashFlowLedgerView

    /** Generate the ledger the deal would have now, WITHOUT storing it. */
    suspend fun preview(dealId: UUID): CashFlowLedgerView

    @Suppress("LongParameterList")
    suspend fun proposeOverride(
        dealId: UUID,
        flowId: UUID,
        paymentDate: LocalDate?,
        amount: BigDecimal?,
        reason: String,
        actor: Actor,
        key: String,
    ): com.openbank.treasury.domain.cashflow.CashFlowOverride

    suspend fun approveOverride(
        dealId: UUID,
        flowId: UUID,
        overrideId: UUID,
        actor: Actor,
        key: String,
    ): com.openbank.treasury.domain.cashflow.CashFlowOverride

    @Suppress("LongParameterList")
    suspend fun rejectOverride(
        dealId: UUID,
        flowId: UUID,
        overrideId: UUID,
        reason: String,
        actor: Actor,
        key: String,
    ): com.openbank.treasury.domain.cashflow.CashFlowOverride

    suspend fun conflicts(dealId: UUID): List<com.openbank.treasury.domain.cashflow.CashFlowConflict>

    /** One deal's overrides, optionally by status (the approver's view of a deal). */
    suspend fun overrides(
        dealId: UUID,
        status: com.openbank.treasury.domain.cashflow.OverrideStatus?,
    ): List<com.openbank.treasury.domain.cashflow.CashFlowOverride>

    /** The four-eyes queue across deals: overrides in [status], oldest first, at most [limit]. */
    suspend fun overrideQueue(
        status: com.openbank.treasury.domain.cashflow.OverrideStatus,
        limit: Int,
    ): List<com.openbank.treasury.domain.cashflow.CashFlowOverride>

    suspend fun versions(dealId: UUID): List<com.openbank.treasury.application.port.out.DealVersionView>

    suspend fun calendar(
        from: LocalDate,
        to: LocalDate,
        bookId: String?,
        types: Set<TreasuryEventType>?,
    ): List<TreasuryCalendarEvent>
}

/**
 * The custodian's period-end statement of holdings (ADR-0337 amendment): ingested like a nostro
 * camt.053, served per date, never derived.
 */
interface PortfolioStatementUseCase {
    /**
     * Store a parsed semt.002 for the configured entity and safekeeping account. A replay of
     * [idempotencyKey] with the same bytes, or the same bytes under a new key for the same date,
     * returns the stored version (idempotent re-ingestion); the key reused on other bytes is a
     * conflict. Different bytes for a date that already has a statement is a CORRECTION: stored
     * as the next version, superseding the current one, which is kept.
     */
    suspend fun upload(
        statement: CustodyStatement,
        sha256: String,
        idempotencyKey: String,
        actor: Actor,
    ): StoredPortfolioStatement

    /** The current snapshot at [date]. @throws com.openbank.treasury.application.port.out.PortfolioSnapshotMissingException when none (409). */
    suspend fun periodEnd(date: LocalDate): CompletePortfolio

    /** Every version stored for [date], oldest first: the correction trail. */
    suspend fun versions(date: LocalDate): List<StoredPortfolioStatement>
}
