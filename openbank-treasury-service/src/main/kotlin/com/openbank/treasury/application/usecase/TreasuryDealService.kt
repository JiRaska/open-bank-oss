// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.treasury.application.port.`in`.AccrualRun
import com.openbank.treasury.application.port.`in`.CounterpartyExposure
import com.openbank.treasury.application.port.`in`.CurrencyPosition
import com.openbank.treasury.application.port.`in`.DealView
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.`in`.PositionBasis
import com.openbank.treasury.application.port.`in`.PositionReport
import com.openbank.treasury.application.port.`in`.SimulatedMarketRun
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealNotFoundException
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.ExposureGuard
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.UnknownCounterpartyException
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.CashFlowGenerated
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealBooked
import com.openbank.treasury.domain.model.DealCalendars
import com.openbank.treasury.domain.model.DealConfirmed
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.DealMatured
import com.openbank.treasury.domain.model.DealReversed
import com.openbank.treasury.domain.model.DealSettled
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LedgerRole
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.MatrixPostings
import com.openbank.treasury.domain.model.NoPostingRuleException
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingRules
import com.openbank.treasury.domain.model.ProductLimitApplied
import com.openbank.treasury.domain.model.ProductLimitPolicy
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.reference.Ifrs9Category
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
// LongParameterList: a plain application class wired by one CDI producer; every parameter is a
// collaborator or a deployment decision, and bundling them would only hide which is which.
@Suppress("TooManyFunctions", "LongParameterList")
class TreasuryDealService(
    private val deals: DealRepository,
    private val counterparties: CounterpartyRepository,
    private val ledger: LedgerPostingPort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val fxMid: FxMidRatePort = FxMidRatePort.NONE,
    private val fxTolerance: FxRateTolerance = FxRateTolerance.DISABLED,
    private val confirmationRequired: Boolean = true,
    /** ADR-0315 D9: when set and enabled, a simulated counterparty confirms only deals struck at its quote. */
    private val simulatedQuotes: SimulatedQuoteService? = null,
    /**
     * ADR-0315 D4 product limits (`openbank.treasury.product-limits`). Required, no default: a
     * service constructed without a mandate must not book as though every product were permitted.
     */
    private val productLimits: ProductLimitPolicy,
    /**
     * ADR-0344 D3/D4: reference validation and the cash-flow ledger. Null only in unit tests of the
     * lifecycle alone; the CDI producer always wires it.
     */
    private val schedules: ScheduleService? = null,
    /** ADR-0344 catalogue: the product mandate from the reference store (config seeds the rest). */
    private val mandate: (suspend () -> ProductLimitPolicy)? = null,
    /** ADR-0344 D7: the stored cash-flow ledger the posting-rule matrix posts from. */
    private val cashFlows: CashFlowRepository? = null,
    /** ADR-0344 D7: the IFRS 9 category of a deal's book (reference `book` record); amortised cost when none. */
    private val bookCategory: suspend (bookId: String?) -> Ifrs9Category = { Ifrs9Category.AMORTISED_COST },
    /**
     * ADR-0344 D6: runs before a deal is cancelled or reversed (and, later, corrected): blocks the
     * change while a payment of the deal is released, withdraws its PROPOSED payments.
     */
    private val paymentGuard: (suspend (dealId: UUID) -> Unit)? = null,
) : TreasuryDealUseCase {

    private suspend fun policy(): ProductLimitPolicy = mandate?.invoke() ?: productLimits

    override suspend fun draft(command: DraftDealCommand, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, DRAFT, null)?.let { return it } }
        counterparties.findById(command.counterpartyId) ?: throw UnknownCounterpartyException(command.counterpartyId)
        val now = clock.instant()
        val today = bankToday()
        val tradeDate = command.tradeDate ?: today
        val valueDate = command.valueDate
            ?: if (command.product == ProductType.FX_SPOT) DealCalendars.spotDate(tradeDate, command.currency) else null
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
            kind = command.kind,
            legs = command.legs,
            header = command.header,
        )
        val deal = vetTerms(drafted)
        return deals.save(deal, command = key?.let { CommandKey(it, DRAFT, deal.id) })
    }

    override suspend fun editDraft(dealId: UUID, command: DraftDealCommand, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, EDIT, dealId)?.let { return it } }
        val existing = load(dealId)
        counterparties.findById(command.counterpartyId) ?: throw UnknownCounterpartyException(command.counterpartyId)
        val tradeDate = command.tradeDate ?: existing.tradeDate
        val valueDate = command.valueDate
            ?: if (command.product == ProductType.FX_SPOT) DealCalendars.spotDate(tradeDate, command.currency) else null
        // The new terms are built exactly as a new draft by the deal's creator would be: every
        // invariant of a draft holds again, including an agent draft's rationale and inputs.
        val replacement = Deal.draft(
            id = existing.id,
            product = command.product,
            counterpartyId = command.counterpartyId,
            currency = command.currency,
            principal = command.principal,
            rate = command.rate,
            tradeDate = tradeDate,
            valueDate = requireNotNull(valueDate) { "valueDate is required" },
            maturityDate = command.maturityDate,
            actor = existing.createdBy,
            at = existing.createdAt,
            rationale = command.rationale ?: existing.rationale,
            inputs = command.inputs ?: existing.inputs,
            fxSide = command.fxSide,
            kind = command.kind,
            legs = command.legs,
            header = command.header,
        )
        val edited = vetTerms(existing.editDraft(replacement, actor, clock.instant()))
        return deals.save(edited, command = cmd(key, EDIT, dealId))
    }

    /**
     * The term checks every path that creates, changes or advances a deal's terms runs — draft,
     * edit, submit, approve — so none can skip one: every named reference effective on the trade
     * date and (#10896) the FX rate re-flagged against fx-service's mid (a flag, never a block).
     * Leg/flat consistency and the work bounds are [Deal]'s own invariants and hold on every copy;
     * product and counterparty limits are evaluated at submit and re-checked at approval.
     */
    private suspend fun vetTerms(deal: Deal): Deal {
        schedules?.requireEffective(deal)
        return if (fxTolerance.enabled) {
            deal.checkRate(fxMid.mid(deal.currency, deal.tradeDate), fxTolerance.tolerancePercent, clock.instant())
        } else {
            deal
        }
    }

    override suspend fun vetCorrection(
        target: Deal,
        override: com.openbank.treasury.domain.model.LimitOverride?,
    ): Pair<Deal, LimitCheck?> {
        val vetted = vetTerms(target)
        val productCheck = policy().evaluate(vetted)
        if (productCheck.breached) throw com.openbank.treasury.domain.model.ProductLimitBreachedException(productCheck)
        if (!vetted.consumesLimitWhenLive) return vetted to null
        val check = limitCheck(vetted)
        if (check.breached && (override == null || check.exposureAfter > override.coversExposureUpTo)) {
            throw com.openbank.treasury.domain.model.LimitBreachedException(check)
        }
        return vetted to check
    }

    override suspend fun submit(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, SUBMIT, dealId)?.let { return it } }
        val deal = vetTerms(load(dealId))
        val check = limitCheck(deal)
        val submitted = deal.submit(actor, check, clock.instant(), policy().evaluate(deal))
        // ADR-0344 D4: the cash-flow ledger is generated at submission and stored with it.
        val generated = schedules?.generate(submitted)
        val withSchedule = generated?.let { submitted.withSchedule(it.write.scheduleVersion, it.hash) } ?: submitted
        val events = generated?.let { g ->
            listOf(
                DealEvent(
                    CashFlowGenerated.EVENT_TYPE,
                    objectMapper.writeValueAsString(
                        CashFlowGenerated(
                            dealId = withSchedule.id,
                            product = withSchedule.product,
                            kind = withSchedule.kind,
                            bookId = withSchedule.bookId,
                            dealVersion = withSchedule.version,
                            scheduleVersion = withSchedule.scheduleVersion,
                            scheduleHash = g.hash,
                            flowCount = g.flows.size,
                            projectedCount = g.flows.count { it.amount == null },
                            carriedOverrides = g.write.carried.size,
                            conflicts = g.write.conflicts.size,
                            occurredAt = withSchedule.updatedAt,
                            flows = g.flows.map(com.openbank.treasury.domain.model.LedgerFlowItem::of),
                        ),
                    ),
                ),
            )
        }.orEmpty()
        return deals.save(
            withSchedule,
            command = cmd(key, SUBMIT, dealId),
            schedule = generated?.write,
            extraEvents = events,
            // A submission that fits consumes the line now: serialised with every other write on it.
            // One that breaches goes to review (PENDING_APPROVAL) and is re-guarded at approval.
            limitGuard = check.takeIf { submitted.consumesLimit && !it.breached }?.let { ExposureGuard.of(it) },
        )
    }

    override suspend fun approve(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, APPROVE, dealId)?.let { return it } }
        val deal = vetTerms(load(dealId))
        // Re-checked at approval: exposure may have moved, and the product mandate may have been
        // tightened, since submission (ADR-0315 D4).
        val productCheck = policy().evaluate(deal)
        val check = limitCheck(deal)
        val booked = deal.approve(actor, check, clock.instant(), productCheck)
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
                    productLimit = productCheck.limit?.let { ProductLimitApplied.of(it, booked.currency) },
                    kind = booked.kind,
                    bookId = booked.bookId,
                    legs = booked.legs,
                    scheduleVersion = booked.scheduleVersion,
                    scheduleHash = booked.scheduleHash,
                ),
            ),
        )
        val guard = check.takeIf {
            booked.consumesLimit
        }?.let { ExposureGuard.of(it, booked.limitOverride?.coversExposureUpTo) }
        return deals.save(booked, event = event, command = cmd(key, APPROVE, dealId), limitGuard = guard)
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
        // Domain transition (authorisation, state) FIRST; the payment withdrawal happens in the save's
        // transaction under the deal lock, so a refused or failed cancel withdraws nothing.
        val cancelled = load(dealId).cancel(actor, clock.instant())
        return deals.save(cancelled, command = cmd(key, CANCEL, dealId), withdrawPayments = paymentGuard != null)
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
                    kind = confirmed.kind,
                ),
            ),
        )
        return deals.save(confirmed, event = event, command = cmd(key, CONFIRM, dealId))
    }

    override suspend fun settle(dealId: UUID, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, SETTLE, dealId)?.let { return it } }
        val deal = load(dealId)
        val settled = deal.settle(actor, bankToday(), clock.instant(), confirmationRequired)
        val ref = if (settled.product.legacy) {
            post(PostingRules.settlement(settled), settled.valueDate, "treasury ${settled.product} settlement")
        } else {
            matrixSettlement(settled)
        }
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
        val matured = deal.mature(actor, bankToday(), clock.instant())
        // ADR-0315 D5: catch the accrual up to maturity first, so the maturity journal clears the
        // accrued account instead of booking the whole interest to income in one amount.
        accrueThrough(deal, deal.maturityDate)
        val ref = if (matured.product.legacy) {
            post(
                PostingRules.maturity(matured, accruedSoFar(deal)),
                matured.maturityDate,
                "treasury ${matured.product} maturity",
            )
        } else {
            matrixMaturity(matured)
        }
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
        // Read-only pre-check before the reversal journal is posted (no side effect); the binding
        // check and the withdrawal run again under the deal lock in the save's transaction.
        paymentGuard?.invoke(dealId)
        check(deal.product.legacy || deal.state != DealState.SETTLED) {
            "a settled ${deal.product} deal is changed by a correction (ADR-0344 D7), not reversed"
        }
        val accrued = if (deal.state == DealState.SETTLED) accruedSoFar(deal) else BigDecimal.ZERO
        val ref = PostingRules.reversal(reversed, deal.state, accrued)
            ?.let {
                post(
                    pinToOriginalAccounts(it, deals.journals(deal.id)),
                    bankToday(),
                    "treasury ${deal.product} reversal",
                )
            }
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
                    kind = reversed.kind,
                ),
            ),
        )
        return deals.save(reversed, ref, event, cmd(key, REVERSE, dealId), withdrawPayments = paymentGuard != null)
    }

    override suspend fun sendConfirmation(
        dealId: UUID,
        documentId: String?,
        channel: String?,
        actor: Actor,
        key: String?,
    ): Deal {
        key?.let { k -> replay(k, SEND_CONFIRMATION, dealId)?.let { return it } }
        val sent = load(dealId).recordConfirmationSent(actor, clock.instant(), documentId, channel)
        return deals.save(sent, command = cmd(key, SEND_CONFIRMATION, dealId))
    }

    override suspend fun get(dealId: UUID): DealView = DealView(load(dealId), deals.journals(dealId))

    override suspend fun list(state: DealState?): List<Deal> = deals.list(state)

    override suspend fun counterparties(): List<CounterpartyExposure> {
        val overrides = deals.pendingLimitOverrides()
        // The limit view's response enum is closed at BANK | CENTRAL_BANK (openapi): the corporate and
        // issuer records ADR-0344 D3 added are served by GET /reference/counterparty, not here.
        return counterparties.list().filter { it.kind in LIMIT_VIEW_KINDS }.flatMap { cp -> exposures(cp, overrides) }
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
     * Outstanding principal on [asOf]: a counted deal whose value date <= asOf < maturity date, so
     * a deal is in the position from its value date and out of it on its maturity date.
     *
     * The basis follows the bank's accounting day (Europe/Prague, never the JVM zone): up to and
     * including today the position is ACTUAL — only SETTLED or MATURED deals, whose cash moved;
     * after today it is PROJECTED — BOOKED and CONFIRMED deals are added on their contracted dates.
     * DRAFT and PENDING_APPROVAL are not concluded, CANCELLED and REVERSED hold nothing.
     */
    override suspend fun positions(asOf: LocalDate?): PositionReport {
        val today = AccountingClock.bank(clock).today()
        val date = asOf ?: today
        val basis = if (date.isAfter(today)) PositionBasis.PROJECTED else PositionBasis.ACTUAL
        val states = if (basis == PositionBasis.ACTUAL) ACTUAL_STATES else ACTUAL_STATES + CONTRACTED_STATES
        val live = states.flatMap { deals.list(it) }.filter {
            !it.valueDate.isAfter(date) && it.maturityDate.isAfter(date)
        }
        val positions = Deal.SUPPORTED_CURRENCIES.sorted().map { ccy ->
            val inCcy = live.filter { it.currency == ccy }
            fun sum(p: ProductType) = inCcy.filter { it.product == p }.sumOf { it.principal }
            CurrencyPosition(
                currency = ccy,
                placed = sum(ProductType.MM_PLACEMENT),
                // Lombard borrowing from ČNB is a borrowing: it reduces the net like an interbank one.
                borrowed = sum(ProductType.MM_BORROWING) + sum(ProductType.CNB_LOMBARD),
                atCnb = sum(ProductType.CNB_DEPOSIT_FACILITY),
                dealCount = inCcy.count { it.product in POSITION_PRODUCTS },
            )
        }
        return PositionReport(date, today, basis, states, positions)
    }

    /**
     * One deal failing (ledger down, a 422) must not stop the rest of the pass, and must not be
     * swallowed either: it is counted and its cause returned for the scheduler to log.
     */
    override suspend fun runSimulatedMarket(): SimulatedMarketRun {
        val today = bankToday()
        val market = Actor.SIMULATED_MARKET
        // ADR-0315 D9: the simulated counterparty confirms BOOKED deals first (a SYNTHETIC
        // confirmation, `simulated = true` on the event and SIMULATED_MARKET on the timeline), so
        // the settlement pass below finds them CONFIRMED whatever `confirmationRequired` says. A
        // quoted deal struck off the counterparty's quote is DECLINED: it stays BOOKED for a person.
        val confirmations = deals.list(DealState.BOOKED).filter {
            it.kind == DealKind.ACTUAL && it.product.legacy
        }.map { d -> runCatching { simulatedConfirm(d, market) } }
        val settleable = Deal.settleableStates(confirmationRequired)
        // ADR-0344: a PLANNED or HYPOTHETICAL deal is never confirmed, settled or matured by the market.
        val moves = deals.dueForSettlement(today, settleable).map { d -> runCatching { settle(d.id, market) } } +
            deals.dueForMaturity(today).map { d -> runCatching { mature(d.id, market) } }
        return SimulatedMarketRun(
            moved = confirmations.count { it.getOrNull() == true } + moves.count { it.isSuccess },
            failures = confirmations.mapNotNull { it.exceptionOrNull() } + moves.mapNotNull { it.exceptionOrNull() },
            declined = confirmations.count { it.getOrNull() == false },
        )
    }

    /** True = confirmed; false = the simulated counterparty declined (struck off its quote). */
    private suspend fun simulatedConfirm(deal: Deal, market: Actor): Boolean {
        val quote = simulatedQuotes?.quoteFor(deal)
        if (quote != null && !quote.accepts(deal)) return false
        val reference = quote?.let {
            "synthetic quote bid ${it.bid} / ask ${it.ask} % (${it.curveIndex}, curve set ${it.curveSetId})"
        }
        confirm(deal.id, reference, market)
        return true
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

    override suspend fun postOutflow(dealId: UUID, through: LocalDate): Int {
        val deal = load(dealId)
        if (deal.product.legacy || deal.state != DealState.SETTLED) return 0
        return matrixAccrueAndPay(deal, minOf(through, deal.maturityDate))
    }

    /** Post each day after the last accrued one, up to and including [through]. Returns journals posted. */
    private suspend fun accrueThrough(deal: Deal, through: LocalDate): Int {
        if (!deal.product.legacy) return matrixAccrueAndPay(deal, through)
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

    // ── ADR-0344 D7: the posting-rule matrix products ─────────────────────────────────────

    private suspend fun ledgerOf(deal: Deal) =
        checkNotNull(cashFlows) { "no cash-flow store wired" }.flows(deal.id, null)

    /** The commitment memo (trade-date products) and the value-date settlement; the last one is the deal's ref. */
    private suspend fun matrixSettlement(deal: Deal): LedgerJournalRef {
        val flows = ledgerOf(deal)
        val category = bookCategory(deal.bookId)
        val trade = MatrixPostings.trade(deal)?.let { post(it, deal.valueDate, "treasury ${deal.product} commitment") }
        val settlement = MatrixPostings.settlement(deal, flows, category)
            ?.let { post(it, deal.valueDate, "treasury ${deal.product} settlement") }
        if (trade != null && settlement != null) deals.recordJournal(trade)
        return settlement ?: trade ?: throw NoPostingRuleException("${deal.product} settles with nothing to post")
    }

    /** Accrue every day through [through] and post the flows paid by then; returns journals posted. */
    private suspend fun matrixAccrueAndPay(deal: Deal, through: LocalDate): Int {
        val flows = ledgerOf(deal)
        val journals = deals.journals(deal.id)
        val run = MatrixPostings.run(
            deal,
            flows,
            bookCategory(deal.bookId),
            lastMatrixAccrual(journals),
            paidFlows(journals),
            through,
        )
        run.forEach { (spec, day) ->
            deals.recordJournal(post(spec, day, "treasury ${deal.product} ${spec.event.key} $day"))
        }
        return run.size
    }

    /**
     * Every flow still unposted is paid (the last one can fall after the unadjusted maturity date),
     * then the final exchange or principal, the memo release and any residual amortisation.
     */
    private suspend fun matrixMaturity(deal: Deal): LedgerJournalRef {
        val flows = ledgerOf(deal)
        matrixAccrueAndPay(deal, MatrixPostings.lastPaymentDate(deal, flows))
        val category = bookCategory(deal.bookId)
        val accruedTo = lastMatrixAccrual(deals.journals(deal.id)) ?: deal.valueDate
        val spec =
            MatrixPostings.maturity(deal, flows, category, MatrixPostings.amortisedThrough(deal, flows, accruedTo))
                ?: throw NoPostingRuleException("${deal.product} matures with nothing to post")
        return post(spec, deal.maturityDate, "treasury ${deal.product} maturity")
    }

    private fun lastMatrixAccrual(journals: List<LedgerJournalRef>): LocalDate? = journals
        .filter { it.event == PostingEvent.ACCRUAL }
        .maxOfOrNull { LocalDate.parse(it.idempotencyKey.substringAfterLast(':')) }

    private fun paidFlows(journals: List<LedgerJournalRef>): Set<UUID> = journals
        .filter { it.event == PostingEvent.PAYMENT || it.event == PostingEvent.AMORTISATION }
        .map { UUID.fromString(it.idempotencyKey.substringAfterLast(':')) }.toSet()

    /**
     * A reversal offsets the accounts the deal's own journals actually landed on, NOT whatever the
     * ledger's class map says today: the map can be re-pointed by a reviewed migration between a
     * settlement and its reversal, and a reversal that re-resolved would then leave the original
     * account uncleared. A leg with no recorded account (a deal posted before accounts were recorded)
     * stays a class leg and resolves now; the first recorded posting of a class/role/currency wins.
     */
    private fun pinToOriginalAccounts(spec: JournalSpec, journals: List<LedgerJournalRef>): JournalSpec {
        val original = LinkedHashMap<Triple<String, LedgerRole, String>, java.util.UUID>()
        journals.sortedBy { it.postedAt }.flatMap { it.accounts }.forEach {
            original.putIfAbsent(Triple(it.accountingClass, it.role, it.currency), it.glAccountId)
        }
        return spec.copy(
            lines = spec.lines.map {
                it.copy(glAccountId = original[Triple(it.accountingClass, it.role, it.currency)])
            },
        )
    }

    private suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): LedgerJournalRef {
        val posted = ledger.post(spec, entryDate, description)
        return LedgerJournalRef(
            spec.dealId,
            spec.event,
            spec.idempotencyKey,
            posted.journalId,
            clock.instant(),
            posted.accounts,
        )
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

    /**
     * The business day in the bank zone (Europe/Prague), never the JVM default: the pod runs UTC,
     * so `LocalDate.now(clock)` gave yesterday for a deal struck in Prague after 22:00 UTC (summer).
     */
    private fun bankToday(): LocalDate = AccountingClock.bank(clock).today()

    private fun cmd(key: String?, action: String, dealId: UUID) = key?.let { CommandKey(it, action, dealId) }

    private companion object {
        const val MAX_KEY_LENGTH = 128

        /** Cash has moved: what an ACTUAL position counts. */
        val ACTUAL_STATES = setOf(DealState.SETTLED, DealState.MATURED)

        /** Concluded with the counterparty, cash not yet moved: added by a PROJECTED position. */
        val CONTRACTED_STATES = setOf(DealState.BOOKED, DealState.CONFIRMED)

        val POSITION_PRODUCTS = setOf(
            ProductType.MM_PLACEMENT,
            ProductType.MM_BORROWING,
            ProductType.CNB_LOMBARD,
            ProductType.CNB_DEPOSIT_FACILITY,
        )
        const val DRAFT = "DRAFT"
        const val EDIT = "EDIT"
        const val SUBMIT = "SUBMIT"
        const val APPROVE = "APPROVE"
        const val OVERRIDE = "OVERRIDE_LIMIT"
        const val REJECT = "REJECT"
        const val CANCEL = "CANCEL"
        const val CONFIRM = "CONFIRM"
        const val SETTLE = "SETTLE"
        const val MATURE = "MATURE"
        const val REVERSE = "REVERSE"
        const val SEND_CONFIRMATION = "SEND_CONFIRMATION"
        val LIMIT_VIEW_KINDS = setOf(CounterpartyKind.BANK, CounterpartyKind.CENTRAL_BANK)
    }
}
