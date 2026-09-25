// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
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
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.UnknownCounterpartyException
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealBooked
import com.openbank.treasury.domain.model.DealMatured
import com.openbank.treasury.domain.model.DealReversed
import com.openbank.treasury.domain.model.DealSettled
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LimitCheck
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
 */
@Suppress("TooManyFunctions")
class TreasuryDealService(
    private val deals: DealRepository,
    private val counterparties: CounterpartyRepository,
    private val ledger: LedgerPostingPort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : TreasuryDealUseCase {

    override suspend fun draft(command: DraftDealCommand, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, DRAFT, null)?.let { return it } }
        counterparties.findById(command.counterpartyId) ?: throw UnknownCounterpartyException(command.counterpartyId)
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val deal = Deal.draft(
            id = Ids.newId(),
            product = command.product,
            counterpartyId = command.counterpartyId,
            currency = command.currency,
            principal = command.principal,
            rate = command.rate,
            tradeDate = command.tradeDate ?: today,
            valueDate = command.valueDate,
            maturityDate = command.maturityDate,
            actor = actor,
            at = now,
            rationale = command.rationale,
        )
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
                ),
            ),
        )
        return deals.save(booked, event = event, command = cmd(key, APPROVE, dealId))
    }

    override suspend fun reject(dealId: UUID, reason: String, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, REJECT, dealId)?.let { return it } }
        return deals.save(load(dealId).reject(actor, reason, clock.instant()), command = cmd(key, REJECT, dealId))
    }

    override suspend fun cancel(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, CANCEL, dealId)?.let { return it } }
        return deals.save(load(dealId).cancel(actor, clock.instant()), command = cmd(key, CANCEL, dealId))
    }

    override suspend fun settle(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, SETTLE, dealId)?.let { return it } }
        val deal = load(dealId)
        val settled = deal.settle(actor, LocalDate.now(clock), clock.instant())
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
                ),
            ),
        )
        return deals.save(settled, ref, event, cmd(key, SETTLE, dealId))
    }

    override suspend fun mature(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, MATURE, dealId)?.let { return it } }
        val deal = load(dealId)
        val matured = deal.mature(actor, LocalDate.now(clock), clock.instant())
        val ref = post(PostingRules.maturity(matured), matured.maturityDate, "treasury ${matured.product} maturity")
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
        val ref = PostingRules.reversal(reversed, deal.state)
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
                ),
            ),
        )
        return deals.save(reversed, ref, event, cmd(key, REVERSE, dealId))
    }

    override suspend fun get(dealId: UUID): DealView = DealView(load(dealId), deals.journals(dealId))

    override suspend fun list(state: DealState?): List<Deal> = deals.list(state)

    override suspend fun counterparties(): List<CounterpartyExposure> = counterparties.list().flatMap { cp ->
        Deal.SUPPORTED_CURRENCIES.sorted().mapNotNull { ccy ->
            if (!cp.limits.containsKey(ccy)) return@mapNotNull null
            CounterpartyExposure(cp, ccy, cp.limitFor(ccy), deals.exposure(cp.id, ccy, null))
        }
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
                borrowed = sum(ProductType.MM_BORROWING),
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
        val outcomes = deals.dueForSettlement(today).map { d -> runCatching { settle(d.id, Actor.SIMULATED_MARKET) } } +
            deals.dueForMaturity(today).map { d -> runCatching { mature(d.id, Actor.SIMULATED_MARKET) } }
        return SimulatedMarketRun(
            moved = outcomes.count { it.isSuccess },
            failures = outcomes.mapNotNull { it.exceptionOrNull() },
        )
    }

    private suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): LedgerJournalRef {
        val journalId = ledger.post(spec, entryDate, description)
        return LedgerJournalRef(spec.dealId, spec.event, spec.idempotencyKey, journalId, clock.instant())
    }

    private suspend fun limitCheck(deal: Deal): LimitCheck {
        val cp = counterparties.findById(deal.counterpartyId) ?: throw UnknownCounterpartyException(deal.counterpartyId)
        val exposure = if (deal.product.isAsset) deals.exposure(cp.id, deal.currency, deal.id) else BigDecimal.ZERO
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
        const val REJECT = "REJECT"
        const val CANCEL = "CANCEL"
        const val SETTLE = "SETTLE"
        const val MATURE = "MATURE"
        const val REVERSE = "REVERSE"
    }
}
