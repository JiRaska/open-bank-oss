// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.treasury.application.port.`in`.AccrualRun
import com.openbank.treasury.application.port.`in`.CounterpartyExposure
import com.openbank.treasury.application.port.`in`.CurrencyPosition
import com.openbank.treasury.application.port.`in`.DealView
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.`in`.SimulatedMarketRun
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealNotFoundException
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.UnknownCounterpartyException
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.DayCount
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealBooked
import com.openbank.treasury.domain.model.DealConfirmed
import com.openbank.treasury.domain.model.DealMatured
import com.openbank.treasury.domain.model.DealReversed
import com.openbank.treasury.domain.model.DealSettled
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingRules
import com.openbank.treasury.domain.model.ProductType
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Deal lifecycle orchestration. Posting order is deliberate: the ledger journal is posted FIRST
 * (idempotent on `treasury:<dealId>:<event>`), and only then are the state change, the journal
 * reference and the outbox event committed together. A crash between the two leaves the deal in
 * its old state with the journal already in the ledger; the retry re-posts under the same key,
 * the ledger returns the ORIGINAL entry, and the state change commits — never a double posting.
 * Nothing posts before BOOKED: only settle / mature / reverse-from-SETTLED call the ledger.
 *
 * [confirmationRequired] (`openbank.treasury.confirmation.required`, default true, ADR-0315 D2):
 * settlement needs a CONFIRMED deal. False keeps the pre-CONFIRMED behaviour (settle straight from
 * BOOKED) for a deployment that has no confirmation step yet; there is no data migration either way.
 */
@Suppress("TooManyFunctions")
class TreasuryDealService(
    private val deals: DealRepository,
    private val counterparties: CounterpartyRepository,
    private val ledger: LedgerPostingPort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val fxMid: FxMidRatePort = FxMidRatePort.NONE,
    private val fxTolerance: FxRateTolerance = FxRateTolerance.DISABLED,
    private val confirmationRequired: Boolean = true,
) : TreasuryDealUseCase {

    override suspend fun draft(command: DraftDealCommand, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, DRAFT, null)?.let { return it } }
        counterparties.findById(command.counterpartyId) ?: throw UnknownCounterpartyException(command.counterpartyId)
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val tradeDate = command.tradeDate ?: today
        val valueDate = command.valueDate
            ?: if (command.product == ProductType.FX_SPOT) DayCount.spotDate(tradeDate) else null
        val drafted = Deal.draft(
            id = Ids.newId(),
            product = command.product,
            counterpartyId = command.counterpartyId,
            currency = command.currency,
            principal = command.principal,
            rate = command.rate,
            tradeDate = tradeDate,
            valueDate = requireNotNull(valueDate) { "valueDate is required" },
            maturityDate = command.maturityDate,
            actor = actor,
            at = now,
            rationale = command.rationale,
            inputs = command.inputs,
            fxSide = command.fxSide,
        )
        // #10896: flag (never block) an FX deal rate outside tolerance of fx-service's mid.
        val deal = if (fxTolerance.enabled) {
            drafted.checkRate(fxMid.mid(drafted.currency, tradeDate), fxTolerance.tolerancePercent, now)
        } else {
            drafted
        }
        return deals.save(deal, command = key?.let { CommandKey(it, DRAFT, deal.id) })
    }

    override suspend fun submit(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, SUBMIT, dealId)?.let { return it } }
        val deal = load(dealId)
        return deals.save(deal.submit(actor, limitCheck(deal), clock.instant()), command = cmd(key, SUBMIT, dealId))
    }

    override suspend fun approve(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, APPROVE, dealId)?.let { return it } }
        val deal = load(dealId)
        // Re-checked at approval: exposure may have moved since submission.
        val booked = deal.approve(actor, limitCheck(deal), clock.instant())
        val event = DealEvent(
            DealBooked.EVENT_TYPE,
            objectMapper.writeValueAsString(
                DealBooked(
                    dealId = booked.id,
                    product = booked.product,
                    counterpartyId = booked.counterpartyId,
                    currency = booked.currency,
                    principal = booked.principal,
                    rate = booked.rate,
                    valueDate = booked.valueDate,
                    maturityDate = booked.maturityDate,
                    createdBy = booked.createdBy.id,
                    approvedBy = actor.id,
                    occurredAt = booked.updatedAt,
                    fxSide = booked.fx?.side,
                    counterAmount = booked.fx?.counterAmount,
                ),
            ),
        )
        return deals.save(booked, event = event, command = cmd(key, APPROVE, dealId))
    }

    override suspend fun overrideLimit(dealId: UUID, reason: String, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, OVERRIDE, dealId)?.let { return it } }
        val deal = load(dealId)
        // Measured now; approval re-checks, and refuses if exposure has grown past what was overridden.
        val overridden = deal.overrideLimit(actor, reason, limitCheck(deal), clock.instant())
        return deals.save(overridden, command = cmd(key, OVERRIDE, dealId))
    }

    override suspend fun reject(dealId: UUID, reason: String, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, REJECT, dealId)?.let { return it } }
        return deals.save(load(dealId).reject(actor, reason, clock.instant()), command = cmd(key, REJECT, dealId))
    }

    override suspend fun cancel(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, CANCEL, dealId)?.let { return it } }
        return deals.save(load(dealId).cancel(actor, clock.instant()), command = cmd(key, CANCEL, dealId))
    }

    override suspend fun confirm(dealId: UUID, reference: String?, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, CONFIRM, dealId)?.let { return it } }
        val confirmed = load(dealId).confirm(actor, clock.instant(), reference)
        val event = DealEvent(
            DealConfirmed.EVENT_TYPE,
            objectMapper.writeValueAsString(
                DealConfirmed(
                    dealId = confirmed.id,
                    product = confirmed.product,
                    counterpartyId = confirmed.counterpartyId,
                    currency = confirmed.currency,
                    principal = confirmed.principal,
                    valueDate = confirmed.valueDate,
                    confirmedBy = actor.id,
                    simulated = actor == Actor.SIMULATED_MARKET,
                    occurredAt = confirmed.updatedAt,
                ),
            ),
        )
        return deals.save(confirmed, event = event, command = cmd(key, CONFIRM, dealId))
    }

    override suspend fun settle(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, SETTLE, dealId)?.let { return it } }
        val deal = load(dealId)
        val settled = deal.settle(actor, LocalDate.now(clock), clock.instant(), confirmationRequired)
        val ref = post(PostingRules.settlement(settled), settled.valueDate, "treasury ${settled.product} settlement")
        val event = DealEvent(
            DealSettled.EVENT_TYPE,
            objectMapper.writeValueAsString(
                DealSettled(
                    dealId = settled.id,
                    product = settled.product,
                    counterpartyId = settled.counterpartyId,
                    currency = settled.currency,
                    principal = settled.principal,
                    valueDate = settled.valueDate,
                    maturityDate = settled.maturityDate,
                    ledgerJournalId = ref.journalId,
                    occurredAt = settled.updatedAt,
                    fxSide = settled.fx?.side,
                    counterAmount = settled.fx?.counterAmount,
                ),
            ),
        )
        return deals.save(settled, ref, event, cmd(key, SETTLE, dealId))
    }

    override suspend fun mature(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, MATURE, dealId)?.let { return it } }
        val deal = load(dealId)
        val matured = deal.mature(actor, LocalDate.now(clock), clock.instant())
        // ADR-0315 D5: catch the accrual up to maturity first, so the maturity journal clears the
        // accrued account instead of booking the whole interest to income in one amount.
        accrueThrough(deal, deal.maturityDate)
        val ref = post(
            PostingRules.maturity(matured, accruedSoFar(deal)),
            matured.maturityDate,
            "treasury ${matured.product} maturity",
        )
        val event = DealEvent(
            DealMatured.EVENT_TYPE,
            objectMapper.writeValueAsString(
                DealMatured(
                    dealId = matured.id,
                    product = matured.product,
                    counterpartyId = matured.counterpartyId,
                    currency = matured.currency,
                    principal = matured.principal,
                    interest = matured.interest,
                    maturityDate = matured.maturityDate,
                    ledgerJournalId = ref.journalId,
                    occurredAt = matured.updatedAt,
                ),
            ),
        )
        return deals.save(matured, ref, event, cmd(key, MATURE, dealId))
    }

    override suspend fun reverse(dealId: UUID, reason: String, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, REVERSE, dealId)?.let { return it } }
        val deal = load(dealId)
        val reversed = deal.reverse(actor, reason, clock.instant())
        val accrued = if (deal.state == DealState.SETTLED) accruedSoFar(deal) else BigDecimal.ZERO
        val ref = PostingRules.reversal(reversed, deal.state, accrued)
            ?.let { post(it, LocalDate.now(clock), "treasury ${deal.product} reversal") }
        val event = DealEvent(
            DealReversed.EVENT_TYPE,
            objectMapper.writeValueAsString(
                DealReversed(
                    dealId = reversed.id,
                    product = reversed.product,
                    counterpartyId = reversed.counterpartyId,
                    currency = reversed.currency,
                    principal = reversed.principal,
                    reversedBy = actor.id,
                    reason = reason,
                    ledgerJournalId = ref?.journalId,
                    occurredAt = reversed.updatedAt,
                    fxSide = reversed.fx?.side,
                    counterAmount = reversed.fx?.counterAmount,
                ),
            ),
        )
        return deals.save(reversed, ref, event, cmd(key, REVERSE, dealId))
    }

    override suspend fun get(dealId: UUID): DealView = DealView(load(dealId), deals.journals(dealId))

    override suspend fun list(state: DealState?): List<Deal> = deals.list(state)

    override suspend fun counterparties(): List<CounterpartyExposure> {
        val overrides = deals.pendingLimitOverrides()
        return counterparties.list().flatMap { cp -> exposures(cp, overrides) }
    }

    private suspend fun exposures(cp: Counterparty, overrides: List<Deal>): List<CounterpartyExposure> =
        Deal.SUPPORTED_CURRENCIES.sorted().mapNotNull { ccy ->
            if (!cp.limits.containsKey(ccy)) return@mapNotNull null
            CounterpartyExposure(
                counterparty = cp,
                currency = ccy,
                limit = cp.limitFor(ccy),
                exposure = deals.exposure(cp.id, ccy, null),
                activeOverrides = overrides.count { it.holdsActiveLimitOverride(cp.id, ccy) },
            )
        }

    /**
     * Outstanding principal on [asOf]: a deal that has SETTLED (or since MATURED) and whose
     * value date <= asOf < maturity date. Reversed and never-settled deals hold no position.
     */
    override suspend fun positions(asOf: LocalDate): List<CurrencyPosition> {
        val live = (deals.list(DealState.SETTLED) + deals.list(DealState.MATURED)).filter {
            !it.valueDate.isAfter(asOf) && it.maturityDate.isAfter(asOf)
        }
        return Deal.SUPPORTED_CURRENCIES.sorted().map { ccy ->
            val inCcy = live.filter { it.currency == ccy }
            fun sum(p: ProductType) = inCcy.filter { it.product == p }.sumOf { it.principal }
            CurrencyPosition(
                currency = ccy,
                placed = sum(ProductType.MM_PLACEMENT),
                // Lombard borrowing from ČNB is a borrowing: it reduces the net like an interbank one.
                borrowed = sum(ProductType.MM_BORROWING) + sum(ProductType.CNB_LOMBARD),
                atCnb = sum(ProductType.CNB_DEPOSIT_FACILITY),
            )
        }
    }

    /**
     * One deal failing (ledger down, a 422) must not stop the rest of the pass, and must not be
     * swallowed either: it is counted and its cause returned for the scheduler to log.
     */
    override suspend fun runSimulatedMarket(): SimulatedMarketRun {
        val today = LocalDate.now(clock)
        // ADR-0315 D9: the simulated counterparty confirms every BOOKED deal first (a SYNTHETIC
        // confirmation, `simulated = true` on the event and SIMULATED_MARKET on the timeline), so
        // the settlement pass below finds it CONFIRMED whatever `confirmationRequired` says.
        val market = Actor.SIMULATED_MARKET
        val confirmed = deals.list(DealState.BOOKED).map { d -> runCatching { confirm(d.id, null, market) } }
        val settleable = Deal.settleableStates(confirmationRequired)
        val outcomes = confirmed +
            deals.dueForSettlement(today, settleable).map { d -> runCatching { settle(d.id, market) } } +
            deals.dueForMaturity(today).map { d -> runCatching { mature(d.id, market) } }
        return SimulatedMarketRun(
            moved = outcomes.count { it.isSuccess },
            failures = outcomes.mapNotNull { it.exceptionOrNull() },
        )
    }

    /**
     * ADR-0315 D5. One deal failing (ledger down) must not stop the pass, and must not be swallowed:
     * it is counted and returned for the scheduler to log. Idempotent: a day already accrued is
     * skipped here, and the ledger deduplicates on the per-day key if two passes race.
     */
    override suspend fun accrueInterest(asOf: LocalDate): AccrualRun {
        val outcomes = deals.list(DealState.SETTLED).map { d ->
            runCatching { accrueThrough(d, minOf(asOf, d.maturityDate)) }
        }
        return AccrualRun(
            journals = outcomes.sumOf { it.getOrDefault(0) },
            failures = outcomes.mapNotNull { it.exceptionOrNull() },
        )
    }

    /** Post each day after the last accrued one, up to and including [through]. Returns journals posted. */
    private suspend fun accrueThrough(deal: Deal, through: LocalDate): Int {
        var day = (lastAccrued(deal) ?: deal.valueDate).plusDays(1)
        var posted = 0
        while (!day.isAfter(through)) {
            PostingRules.accrual(deal, day)?.let { spec ->
                deals.recordJournal(post(spec, day, "treasury ${deal.product} accrual $day"))
                posted++
            }
            day = day.plusDays(1)
        }
        return posted
    }

    /** The last day with a recorded accrual; a day that adds nothing posts no journal and is not recorded. */
    private suspend fun lastAccrued(deal: Deal): LocalDate? = deals.journals(deal.id)
        .filter { it.event == PostingEvent.ACCRUED }
        .maxOfOrNull { LocalDate.parse(it.idempotencyKey.substringAfterLast(':')) }

    /** Σ of the posted dailies — exactly the cumulative accrual at the last recorded day. */
    private suspend fun accruedSoFar(deal: Deal): BigDecimal =
        lastAccrued(deal)?.let { PostingRules.accruedThrough(deal, it) } ?: BigDecimal.ZERO

    private suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): LedgerJournalRef {
        val journalId = ledger.post(spec, entryDate, description)
        return LedgerJournalRef(spec.dealId, spec.event, spec.idempotencyKey, journalId, clock.instant())
    }

    private suspend fun limitCheck(deal: Deal): LimitCheck {
        val cp = counterparties.findById(deal.counterpartyId) ?: throw UnknownCounterpartyException(deal.counterpartyId)
        val consumes = deal.product.isAsset || deal.product == ProductType.FX_SPOT
        val exposure = if (consumes) deals.exposure(cp.id, deal.limitCurrency, deal.id) else BigDecimal.ZERO
        return LimitCheck.of(cp, deal, exposure)
    }

    private suspend fun load(dealId: UUID): Deal = deals.findById(dealId) ?: throw DealNotFoundException(dealId)

    /**
     * A key already recorded: the same command on the same deal is a replay (answer with the deal
     * as it stands); anything else is a client bug and refused rather than guessed at.
     */
    private suspend fun replay(key: String, action: String, dealId: UUID?): Deal? {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) {
            "Idempotency-Key must be 1..$MAX_KEY_LENGTH characters"
        }
        val prior = deals.findCommand(key) ?: return null
        require(prior.action == action && (dealId == null || prior.dealId == dealId)) {
            "Idempotency-Key was already used for ${prior.action} on another request"
        }
        return load(prior.dealId)
    }

    private fun cmd(key: String?, action: String, dealId: UUID) = key?.let { CommandKey(it, action, dealId) }

    private companion object {
        const val MAX_KEY_LENGTH = 128
        const val DRAFT = "DRAFT"
        const val SUBMIT = "SUBMIT"
        const val APPROVE = "APPROVE"
        const val OVERRIDE = "OVERRIDE_LIMIT"
        const val REJECT = "REJECT"
        const val CANCEL = "CANCEL"
        const val CONFIRM = "CONFIRM"
        const val SETTLE = "SETTLE"
        const val MATURE = "MATURE"
        const val REVERSE = "REVERSE"
    }
}
