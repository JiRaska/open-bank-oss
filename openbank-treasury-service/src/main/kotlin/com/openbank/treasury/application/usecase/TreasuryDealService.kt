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
import com.openbank.treasury.application.port.`in`.IntentReconciliation
import com.openbank.treasury.application.port.`in`.PositionBasis
import com.openbank.treasury.application.port.`in`.PositionReport
import com.openbank.treasury.application.port.`in`.SimulatedMarketRun
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.DealChangedException
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealNotFoundException
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.ExposureGuard
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.port.out.LateJournalMeter
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.PostingIntent
import com.openbank.treasury.application.port.out.PostingIntentAction
import com.openbank.treasury.application.port.out.PostingIntentCloseOutcome
import com.openbank.treasury.application.port.out.PostingIntentPort
import com.openbank.treasury.application.port.out.PostingIntentStatus
import com.openbank.treasury.application.port.out.UnknownCounterpartyException
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.CashFlowGenerated
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.CreditExposure
import com.openbank.treasury.domain.model.CreditMeasure
import com.openbank.treasury.domain.model.CreditTerms
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
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.MatrixPostings
import com.openbank.treasury.domain.model.NoPostingRuleException
import com.openbank.treasury.domain.model.PinnedReversal
import com.openbank.treasury.domain.model.PostedJournalRecord
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingRules
import com.openbank.treasury.domain.model.ProductLimitApplied
import com.openbank.treasury.domain.model.ProductLimitPolicy
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.ReversalAccountsUnknownException
import com.openbank.treasury.domain.reference.Ifrs9Category
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Deal lifecycle orchestration. Posting order is deliberate and intent-first (#13883) for LEGACY
 * products: settle,
 * mature and reverse first record a posting INTENT (keyed `treasury:<dealId>:<event>`, the journal's
 * idempotency key), then post the journal, and only then commit the state change, the journal
 * reference, the outbox event and the intent's completion together. A crash between the ledger's
 * answer and that commit leaves an OPEN intent: it holds its value day's close report, and
 * [reconcilePostingIntents] re-runs the command under the same key — the ledger returns the
 * ORIGINAL entry and the state change commits — never a double posting.
 * Matrix products (ADR-0344 D7) write NO intent: they post under deterministic per-event or per-flow
 * keys (a replay returns the original journal), and a crash after the ledger post stays an open residual.
 * Nothing posts before BOOKED: only settle / mature / reverse-from-SETTLED call the ledger.
 *
 * [confirmationRequired] (`openbank.treasury.confirmation.required`, default true, ADR-0315 D2):
 * settlement needs a CONFIRMED deal. False keeps the pre-CONFIRMED behaviour (settle straight from
 * BOOKED) for a deployment that has no confirmation step yet; there is no data migration either way.
 */
// LongParameterList: a plain application class wired by one CDI producer; every parameter is a
// collaborator or a deployment decision, and bundling them would only hide which is which.
// LargeClass (#13883): the posting-intent guards live beside the lifecycle commands they guard, so
// each command and its intent check read together; the split is tracked in #14232.
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
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
     * ADR-0315 D4: the conversion factors and collateral haircuts effective on a date (reference
     * data). Without them a derivative or a collateralised repo is refused, never measured as zero.
     */
    private val creditTerms: suspend (asOf: LocalDate, collateralIsin: String?) -> CreditTerms =
        { _, _ -> CreditTerms.NONE },
    /** ADR-0342: counts first-recorded journals valued on a day that has already ended. */
    private val lateJournals: LateJournalMeter = LateJournalMeter.NONE,
    /** #13883: posting intents. [PostingIntentPort.NONE] only in unit tests of the lifecycle alone. */
    private val intents: PostingIntentPort = PostingIntentPort.NONE,
    /**
     * #13883 review: the reconciler resumes a command as its stored actor only while that is still
     * the actor's own recent decision: no older than this. Anything older is escalated to an
     * operator, who re-issues the command through the API (authorised afresh) or closes the intent.
     */
    private val maxResumeAge: Duration = Duration.ofHours(DEFAULT_MAX_RESUME_HOURS),
    /**
     * `openbank.treasury.posting-intent.grace`: an intent younger than this may still have its own
     * request in flight to the ledger. It is neither resumed, nor closed by an operator, nor taken as
     * proof of absence on a ledger refusal unless this very call created it.
     */
    private val grace: Duration = Duration.ofMinutes(DEFAULT_GRACE_MINUTES),
    /** How many intents one reconciler pass claims. */
    private val claimBatch: Int = DEFAULT_CLAIM_BATCH,
) : TreasuryDealUseCase {

    private val log = System.getLogger(TreasuryDealService::class.java.name)

    private suspend fun policy(): ProductLimitPolicy = mandate?.invoke() ?: productLimits

    override suspend fun draft(command: DraftDealCommand, actor: Actor, key: String?): Deal {
        key?.let { k -> replay(k, DRAFT, null)?.let { return it } }
        // ADR-0344 delivery phases: a product is drafted only once its phase has landed (400, never a
        // 500 later). Phase 2 delivers every product through the posting-rule matrix and CreditExposure.
        require(command.product in ProductType.DELIVERED) { NOT_DELIVERED.format(command.product) }
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
                    rollDirection = booked.header.rollDirection,
                    calendars = booked.header.calendars,
                    businessDayConvention = booked.header.businessDayConvention,
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
        return deals.save(cancelled, command = cmd(key, CANCEL, dealId))
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
            postIntended(
                PostingRules.settlement(settled),
                settled.valueDate,
                "treasury ${settled.product} settlement",
                Intended(PostingIntentAction.SETTLE, actor, key),
            )
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
        val ownKey = lifecycleKey(dealId, PostingEvent.MATURED)
        val resumed = requireNoOtherUnresolved(dealId, ownKey)
        // ADR-0315 D5: catch the accrual up to maturity first, so the maturity journal clears the
        // accrued account instead of booking the whole interest to income in one amount. A resumed
        // maturity caught up before its intent was opened (and nothing accrued since: the intent
        // defers it). Otherwise a catch-up that could not finish fails the maturity instead of
        // posting one that does not clear every accrual.
        if (!resumed) {
            val caughtUp = accrueThrough(deal, deal.maturityDate)
            if (!caughtUp.complete || caughtUp.deferred) {
                throw DealChangedException(
                    "deal $dealId: the accrual could not be caught up to maturity (a posting is in flight); retry",
                )
            }
        }
        val ref = if (matured.product.legacy) {
            postIntended(
                PostingRules.maturity(matured, accruedSoFar(deal)),
                matured.maturityDate,
                "treasury ${matured.product} maturity",
                Intended(PostingIntentAction.MATURE, actor, key),
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
        requireNoOtherUnresolved(dealId, lifecycleKey(dealId, PostingEvent.REVERSED))
        check(deal.product.legacy || deal.state != DealState.SETTLED) {
            "a settled ${deal.product} deal is changed by a correction (ADR-0344 D7), not reversed"
        }
        // The journals this reversal offsets. Re-checked once its intent exists (which stops any
        // further accrual): one posted in between would be left unreversed, so it is refused instead.
        val seen = deals.journals(deal.id)
        val accrued = if (deal.state == DealState.SETTLED) accruedSoFar(deal) else BigDecimal.ZERO
        val rule = PostingRules.reversal(reversed, deal.state, accrued)
        val ref = rule?.let {
            postIntended(
                pinToPostedAccounts(deal, it, seen),
                bankToday(),
                "treasury ${deal.product} reversal",
                Intended(PostingIntentAction.REVERSE, actor, key, reason),
            ) { intent -> requireNoJournalSince(deal.id, seen, intent) }
        }
        // A reversal that posts nothing (the deal never settled) still claims the deal's single
        // lifecycle slot while it decides, so a settlement cannot post in between and leave its
        // journal behind a REVERSED deal: the one-open-per-deal index refuses whichever is second.
        val claim = if (rule == null) claimUnpostedReversal(reversed, actor, key, reason) else null
        // The claim is this attempt's own row (a per-attempt key), so this call only ever releases a
        // claim it created. The save requires it still OPEN and releases it in the SAME transaction as
        // the state change: a reconciler that released it first makes the save abort (no state
        // change). Any other failure releases it here; nothing was posted either way.
        val saved = try {
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
            deals.save(reversed, ref, event, cmd(key, REVERSE, dealId), releaseClaim = claim)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled coroutine cannot suspend: release under NonCancellable so the UPDATE runs.
            claim?.let { withContext(NonCancellable) { intents.refuse(it, UNPOSTED_REVERSAL_ABANDONED) } }
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // released, then rethrown unchanged
        ) {
            claim?.let {
                intents.refuse(it, "$UNPOSTED_REVERSAL_ABANDONED: ${describe(e)}".take(MAX_ERROR_LENGTH))
            }
            throw e
        }
        // No release left here: the save released the claim in its own transaction, so nothing that
        // runs after the commit can fail the caller or leave the claim behind.
        return saved
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
    override suspend fun accrueInterest(asOf: LocalDate): AccrualRun = accrueInterest(asOf, Instant.MAX)

    override suspend fun accrueInterest(asOf: LocalDate, deadline: Instant): AccrualRun {
        val settled = deals.list(DealState.SETTLED)
        var journals = 0
        var assessed = 0
        var unassessed = 0
        var deferred = 0
        val failures = mutableListOf<Throwable>()
        for (d in settled) {
            if (clock.instant().isAfter(deadline)) {
                unassessed++
                continue
            }
            runCatching { accrueThrough(d, minOf(asOf, d.maturityDate), deadline) }
                .onSuccess { progress ->
                    journals += progress.posted
                    // A deal whose day loop hit the deadline is left for the next pass, not assessed.
                    if (progress.complete) assessed++ else unassessed++
                    if (progress.deferred) deferred++
                }
                .onFailure { failures += it }
        }
        return AccrualRun(
            journals = journals,
            failures = failures,
            deals = assessed,
            unassessed = unassessed,
            deferred = deferred,
        )
    }

    /** Journals one deal's accrual posted, and whether it reached its target day before the deadline. */
    private data class AccrualProgress(val posted: Int, val complete: Boolean, val deferred: Boolean = false)

    /**
     * Post each day after the last accrued one, up to and including [through], checking [deadline]
     * before EVERY day: a deal with a long gap cannot run a pass past its budget, which is what makes
     * `TreasuryCloseJobs.staleAfter` a true bound on a live pass (#13882 review). Each journal is
     * valued on its accrual day; the posting adapter books it in the open day (ADR-0342). A posting
     * matrix product (ADR-0344 D7) accrues and pays through [matrixAccrueAndPay] under the same deadline.
     */
    private suspend fun accrueThrough(
        deal: Deal,
        through: LocalDate,
        deadline: Instant = Instant.MAX,
    ): AccrualProgress {
        if (!deal.product.legacy) return matrixAccrueAndPay(deal, through, deadline)
        // #13883 review: a deal is not accrued while a reversal or maturity of it may already hold a
        // journal in the ledger: an OPEN intent of any kind, or a reversal/maturity intent an
        // operator CLOSED (the ledger may have its journal although the deal still reads SETTLED).
        // A re-issue re-posts the same key and the ledger keeps the ORIGINAL journal, which offsets
        // only the accruals that existed when it was decided; a newer accrual would never be offset.
        // The deal stays deferred until that command is re-issued and completes.
        //
        // Scope: THIS deal only. Every other deal accrues and the pass completes, so liveness and
        // healing are unaffected. In the DAY report the deferred deal counts as unaccrued
        // (InterestAccrualScheduler adds AccrualRun.deferred to the refusals), so a day is never
        // reported RAN without it. Checked again before EVERY day (a reversal can be decided while a
        // long catch-up runs); reverse() re-checks the journals once its intent exists.
        var day = (lastAccrued(deal) ?: deal.valueDate).plusDays(1)
        var posted = 0
        while (!day.isAfter(through)) {
            if (clock.instant().isAfter(deadline)) return AccrualProgress(posted, false)
            if (intents.blocksAccrual(deal.id)) {
                log.log(
                    System.Logger.Level.INFO,
                    "deal {0}: accrual deferred from {1}, a posting intent may hold a journal",
                    deal.id,
                    day,
                )
                return AccrualProgress(posted, complete = true, deferred = true)
            }
            val spec = PostingRules.accrual(deal, day)
            if (spec != null) {
                // The accrual day takes the same intent mechanism as a reversal or maturity BEFORE its
                // ledger call. The database admits one OPEN reversal/maturity/accrual intent per deal,
                // so an accrual can never be in flight while a reversal is decided, nor the other way
                // round: whichever opens second is refused (the reversal 409s, the accrual defers).
                val intent = intents.open(accrualIntent(spec, day))
                    ?: return AccrualProgress(posted, complete = true, deferred = true)
                if (intent.status != PostingIntentStatus.COMPLETED) {
                    postGuarded(spec, intent, "treasury ${deal.product} accrual $day")
                    posted++
                }
            }
            day = day.plusDays(1)
        }
        return AccrualProgress(posted, true)
    }

    private fun accrualIntent(spec: JournalSpec, day: LocalDate) = PostingIntent(
        idempotencyKey = spec.idempotencyKey,
        dealId = spec.dealId,
        event = spec.event,
        action = PostingIntentAction.ACCRUE,
        valueDate = day,
        actor = ACCRUAL_ACTOR,
        createdAt = clock.instant(),
    )

    /** Posts under an open intent; a coded ledger refusal that proves absence closes it REFUSED. */
    @Suppress("TooGenericExceptionCaught") // classified, recorded and rethrown unchanged
    private suspend fun postGuarded(spec: JournalSpec, intent: PostingIntent, description: String): LedgerJournalRef =
        try {
            post(spec, intent.valueDate, description)
        } catch (e: Exception) {
            if (ledger.isDefinitiveRefusal(e) && proofOfAbsence(intent)) {
                intents.refuse(spec.idempotencyKey, describe(e))
            }
            throw e
        }

    /**
     * #14222 / #14220 review: a lifecycle command must not post while the deal has another posting
     * that may hold an unapplied journal (OPEN, or closed by an operator): a failed reversal followed
     * by a maturity would otherwise put both into the ledger. True when the deal's only unresolved
     * intent is this command's own key (a re-issue or a resume).
     */
    private suspend fun requireNoOtherUnresolved(dealId: UUID, ownKey: String): Boolean {
        val unresolved = intents.unresolvedFor(dealId)
        val others = unresolved.filter { it.idempotencyKey != ownKey }
        if (others.isNotEmpty()) {
            throw DealChangedException(
                "deal $dealId has an unresolved posting (${others.joinToString { "${it.action} ${it.status}" }}); " +
                    "re-issue or close it first",
            )
        }
        return unresolved.isNotEmpty()
    }

    /**
     * The claim of a reversal that posts no journal: an intent under the reversal's own key, released
     * (REFUSED: no journal exists under it) once the state change commits. If the process dies in
     * between, the reconciler finds the deal already REVERSED and releases it the same way.
     */
    private suspend fun claimUnpostedReversal(reversed: Deal, actor: Actor, key: String?, reason: String): String {
        // Its own key, never the posted reversal's: a resume can then tell a claim (never replayed)
        // from a reversal that may hold a ledger journal (replayed under its key). Unique per attempt,
        // so a second, concurrent reversal cannot reuse (and later release) this attempt's claim:
        // the one-open-per-deal index refuses it, and it answers 409.
        val claimKey = claimKey(reversed.id)
        intents.open(
            PostingIntent(
                idempotencyKey = claimKey,
                dealId = reversed.id,
                event = PostingEvent.REVERSED,
                action = PostingIntentAction.REVERSE,
                valueDate = bankToday(),
                actor = actor,
                reason = reason,
                commandKey = key,
                createdAt = clock.instant(),
            ),
        ) ?: throw DealChangedException(
            "deal ${reversed.id}: another settlement, reversal, maturity or accrual posting is in flight; retry",
        )
        return claimKey
    }

    private fun claimKey(dealId: UUID) = lifecycleKey(dealId, PostingEvent.REVERSED) + CLAIM_MARKER + Ids.randomId()

    private fun lifecycleKey(dealId: UUID, event: PostingEvent) = "treasury:$dealId:${event.key}"

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
        return settlement ?: trade ?: throw NoPostingRuleException("${deal.product} settles with nothing to post")
    }

    /**
     * Accrue every day through [through] and post the flows paid by then, in day order, checking
     * [deadline] before each posting: the next pass recomputes the run from the journals recorded,
     * so stopping between two postings leaves nothing half-done. [post] records each journal.
     */
    private suspend fun matrixAccrueAndPay(
        deal: Deal,
        through: LocalDate,
        deadline: Instant = Instant.MAX,
    ): AccrualProgress {
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
        var posted = 0
        for ((spec, day) in run) {
            if (clock.instant().isAfter(deadline)) return AccrualProgress(posted, false)
            post(spec, day, "treasury ${deal.product} ${spec.event.key} $day")
            posted++
        }
        return AccrualProgress(posted, true)
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
     * A reversal offsets, per GL account, exactly what the deal's own journals posted to that account,
     * NOT whatever the ledger's class map says today: the map can be re-pointed by a reviewed migration
     * during the deal's life, so accruals may have landed on two accounts, and the unwind must clear
     * each by its own amount ([PinnedReversal]). Each reversed journal is regenerated from the posting
     * table and paired with the accounts the ledger recorded for it; a journal with no recorded
     * accounts or a mismatched record fails the reversal (409) and nothing is posted — it is never
     * re-resolved against today's map.
     */
    private fun pinToPostedAccounts(deal: Deal, rule: JournalSpec, journals: List<LedgerJournalRef>): JournalSpec {
        val reversed = journals.sortedBy { it.postedAt }.mapNotNull { ref ->
            val spec = when (ref.event) {
                PostingEvent.SETTLED -> PostingRules.settlement(deal)
                PostingEvent.ACCRUED -> PostingRules.accrual(
                    deal,
                    LocalDate.parse(ref.idempotencyKey.substringAfterLast(':')),
                )
                else -> return@mapNotNull null
            } ?: throw ReversalAccountsUnknownException(
                "journal ${ref.journalId} (${ref.idempotencyKey}) does not match the posting table for deal ${deal.id}",
            )
            if (spec.idempotencyKey != ref.idempotencyKey) {
                throw ReversalAccountsUnknownException(
                    "journal ${ref.journalId} is keyed ${ref.idempotencyKey}, the posting table says ${spec.idempotencyKey}",
                )
            }
            PostedJournalRecord(ref.journalId, spec, ref.accounts)
        }
        return PinnedReversal.build(rule, reversed)
    }

    /**
     * Posts under the spec's idempotency key, then records the journal in its OWN transaction at once
     * (#13882 review). Settle, mature and reverse commit their state change afterwards; if that commit
     * fails, the journal the ledger accepted is still recorded (and counted by the day-close report),
     * and a retry of the command replays the same key (the ledger answers with the original journal)
     * and finds it recorded. The remaining window is a crash between the ledger's answer and this
     * write; a retry under the same key closes it.
     */
    private suspend fun post(spec: JournalSpec, valueDate: LocalDate, description: String): LedgerJournalRef {
        val posted = ledger.post(spec, valueDate, description)
        val ref = LedgerJournalRef(
            spec.dealId,
            spec.event,
            spec.idempotencyKey,
            posted.journalId,
            clock.instant(),
            posted.accounts,
            valueDate,
        )
        // Late = recorded for the first time HERE (not a replay of a recorded key) for an ended day;
        // a refused post threw above and never gets this far (#13882 final review).
        if (deals.recordJournal(ref) && valueDate.isBefore(bankToday())) lateJournals.late(spec.event)
        return ref
    }

    /** The command a lifecycle post belongs to, as its posting intent records it. */
    private data class Intended(
        val action: PostingIntentAction,
        val actor: Actor,
        val commandKey: String?,
        val reason: String? = null,
    )

    /**
     * #13883: records the posting intent BEFORE the ledger is contacted, then posts under its key. The
     * intent's value day is the first attempt's, so a retry on a later day (a reversal is valued on
     * the day it is made) asks for the very journal the first attempt may have created. Only an
     * explicit ledger business refusal of a FRESH intent (no earlier attempt can have reached the
     * ledger) closes it REFUSED; any other failure, and every failure of a resumed intent (the
     * reconciler, a client retry), leaves it open, because the ledger may hold the journal.
     * [DealRepository.save] completes it. Another OPEN reversal/maturity/accrual intent on the deal
     * (the database admits one) refuses the command with 409 before anything is posted.
     */
    private suspend fun postIntended(
        spec: JournalSpec,
        valueDate: LocalDate,
        description: String,
        intended: Intended,
        beforePost: suspend (PostingIntent) -> Unit = {},
    ): LedgerJournalRef {
        val intent = intents.open(
            PostingIntent(
                idempotencyKey = spec.idempotencyKey,
                dealId = spec.dealId,
                event = spec.event,
                action = intended.action,
                valueDate = valueDate,
                actor = intended.actor,
                reason = intended.reason,
                commandKey = intended.commandKey,
                createdAt = clock.instant(),
            ),
        )
            ?: throw DealChangedException(
                "deal ${spec.dealId}: another settlement, reversal, maturity or accrual posting is in flight; retry",
            )
        if (intent.status == PostingIntentStatus.COMPLETED) {
            // Applied already: answer with the recorded journal, never contact the ledger again.
            return deals.journals(spec.dealId).firstOrNull { it.idempotencyKey == spec.idempotencyKey }
                ?: error("posting intent ${spec.idempotencyKey} is COMPLETED but its journal is not recorded")
        }
        beforePost(intent)
        return postGuarded(spec, intent, description)
    }

    /**
     * The ledger's coded refusal is raised AFTER its idempotency replay check found no journal under
     * the key, so it proves absence at that moment. The only way a journal could still appear is an
     * earlier attempt of the same key still being processed by the ledger. A fresh intent has no
     * earlier attempt; an intent older than [grace] has none in flight either (a ledger request does
     * not outlive the grace by orders of magnitude, and the reconciler holds the row's claim). Only a
     * client retry of an intent younger than the grace stays OPEN.
     */
    private fun proofOfAbsence(intent: PostingIntent): Boolean =
        intent.fresh || intent.createdAt.isBefore(clock.instant().minus(grace))

    override suspend fun reconcilePostingIntents(olderThan: Instant): IntentReconciliation {
        var completed = 0
        var escalated = 0
        val failures = mutableListOf<Throwable>()
        for (intent in intents.claimOpen(olderThan, CLAIM_LEASE, claimBatch)) {
            if (!resumable(intent)) {
                escalated++
                // Marked escalated: no later claim takes it, so it cannot starve resumable intents.
                intents.escalate(intent.idempotencyKey, ESCALATED)
                continue
            }
            runCatching { resume(intent) }
                .onSuccess { completed++ }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failures += e
                    if (e is AccrualPreparationFailed && intent.attempts + 1 >= MAX_ACCRUAL_PREPARATION_ATTEMPTS) {
                        // A deterministic failure before the ledger (the deal or the posting table
                        // cannot produce the day) would otherwise retry forever and hold the deal's
                        // slot. Escalated: OPEN, holding its day, alerting (stuck intent), for a person.
                        // A ledger failure never gets here: an outage is resumed however long it lasts.
                        escalated++
                        intents.escalate(
                            intent.idempotencyKey,
                            "escalated after ${intent.attempts + 1} attempts: ${describe(e)}".take(MAX_ERROR_LENGTH),
                        )
                    } else {
                        intents.failed(intent.idempotencyKey, describe(e))
                    }
                }
        }
        return IntentReconciliation(completed, failures, escalated)
    }

    /**
     * Within [maxResumeAge] of the decision. Age, not the calendar: the concern is the stored actor's
     * authority lapsing, which is a matter of elapsed time, and a same-day rule would escalate an
     * intent created at 23:59:50 that is reconciled at 00:00:30. The calendar is already handled by
     * the intent itself: it pins the journal's value day, so a resume after midnight still posts the
     * original day.
     */
    private fun resumable(intent: PostingIntent): Boolean =
        // An accrual day is the system's own posting (no person's authority to lapse): it is resumed
        // however long the ledger was down, or it would hold the deal's one lifecycle slot forever.
        intent.action == PostingIntentAction.ACCRUE ||
            !intent.createdAt.plus(maxResumeAge).isBefore(clock.instant())

    override suspend fun closePostingIntent(
        dealId: UUID,
        event: PostingEvent,
        reason: String,
        actor: Actor,
        key: String?,
    ): PostingIntentCloseOutcome {
        requireClosable(reason, actor, key)
        val request = CloseRequest(dealId, event, reason, actor)
        val intent = intents.openFor(dealId).firstOrNull { it.event == event }
        if (intent != null) {
            // Segregation of duties (#14220 review): the person who decided the posting, and the
            // approver who booked the deal, may not also release the posting's day-close hold.
            if (actor.id == intent.actor.id || actor.id == load(dealId).approvedBy?.id) {
                throw com.openbank.treasury.domain.model.FourEyesViolationException(
                    "the actor who decided this posting or approved the deal may not close its posting intent",
                )
            }
        }
        key?.let { k -> replayedClose(k, request, intent)?.let { return it } }
        intent ?: return PostingIntentCloseOutcome.NOT_OPEN
        // Not while its own command or a reconciler may still be posting it: younger than the grace,
        // or under a live claim, the close is refused (IN_FLIGHT) rather than raced.
        val outcome = intents.closeByOperator(
            intent.idempotencyKey,
            actor,
            reason,
            clock.instant().minus(grace),
            key,
            request.fingerprint(intent.idempotencyKey),
        )
        return if (key == null) outcome else resolveRace(outcome, key, request, intent)
    }

    /**
     * A concurrent close with the same key won the race (NOT_OPEN), or the key was recorded by another
     * request in between (DUPLICATE_KEY): the stored row decides, never a 500.
     */
    private suspend fun resolveRace(
        outcome: PostingIntentCloseOutcome,
        key: String,
        request: CloseRequest,
        intent: PostingIntent,
    ): PostingIntentCloseOutcome = when (outcome) {
        // The intent is no longer OPEN: only a stored close of THIS intent by THIS request matches.
        PostingIntentCloseOutcome.NOT_OPEN -> replayedClose(key, request, null) ?: outcome
        PostingIntentCloseOutcome.DUPLICATE_KEY -> replayedClose(key, request, intent) ?: throw keyReused()
        else -> outcome
    }

    private fun requireClosable(reason: String, actor: Actor, key: String?) {
        key?.let { k ->
            require(k.isNotBlank() && k.length <= MAX_KEY_LENGTH) {
                "Idempotency-Key must be 1..$MAX_KEY_LENGTH characters"
            }
        }
        require(reason.isNotBlank()) { "a reason is required to close a posting intent" }
        if (actor.type != com.openbank.treasury.domain.model.ActorType.HUMAN) {
            throw com.openbank.treasury.domain.model.ActorNotPermittedException(
                "only a person may close a posting intent",
            )
        }
    }

    /**
     * The close previously recorded under [key], judged against this request (#14220 review):
     *  - a different request (fingerprint over the CLOSED intent's key and the request) is 400;
     *  - a later intent of the same event OPEN now (a later claim) is 400: the key belongs to an
     *    earlier close, and answering 204 would report a close that did not happen to this one;
     *  - the closed intent re-opened by a re-issue is 409 POSTING_IN_FLIGHT (its posting runs again);
     *  - otherwise CLOSED: still closed, or since completed by a late journal or refused, the close
     *    this key made did happen. Null when the key was never used.
     */
    private suspend fun replayedClose(
        key: String,
        request: CloseRequest,
        openNow: PostingIntent?,
    ): PostingIntentCloseOutcome? {
        val stored = intents.closedBy(key) ?: return null
        val sameRequest = stored.fingerprint == request.fingerprint(stored.intentKey) &&
            (openNow == null || openNow.idempotencyKey == stored.intentKey)
        return when {
            !sameRequest -> throw keyReused()
            // The close happened. If its journal landed afterwards (COMPLETED, APPLIED_AFTER_CLOSE) or
            // the intent was refused, it is still the outcome of THIS request: answered the same.
            stored.intentStatus == PostingIntentStatus.OPEN -> PostingIntentCloseOutcome.IN_FLIGHT
            else -> PostingIntentCloseOutcome.CLOSED
        }
    }

    private fun keyReused() = IllegalArgumentException("Idempotency-Key already used for a different request")

    /** What a close under one Idempotency-Key must repeat exactly. */
    private data class CloseRequest(val dealId: UUID, val event: PostingEvent, val reason: String, val actor: Actor) {
        /**
         * sha256 over length-prefixed fields (`<length>:<value>`), so no field content can shift a
         * boundary (`|` in a reason or an actor id cannot make two requests collide), bound to the
         * intent INSTANCE it closes (its idempotency key: lifecycle key or per-attempt claim key).
         */
        fun fingerprint(intentKey: String): String = listOf(dealId.toString(), event.name, actor.id, reason, intentKey)
            .joinToString("") { "${it.length}:$it" }
            .let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)) }
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Re-runs the intent's command as its original actor and client key. The domain transition is
     * evaluated afresh: if the deal can no longer take it, the command fails, the failure is recorded
     * and the intent keeps holding its day for an operator, never closed on a guess.
     */
    private suspend fun resume(intent: PostingIntent): Deal = when (intent.action) {
        PostingIntentAction.SETTLE -> settle(intent.dealId, intent.actor, intent.commandKey)
        PostingIntentAction.MATURE -> mature(intent.dealId, intent.actor, intent.commandKey)
        PostingIntentAction.REVERSE ->
            if (CLAIM_MARKER in intent.idempotencyKey) {
                // An unposted reversal's claim is NEVER replayed: re-running reverse() now would decide a
                // privileged action against whatever the deal has become (a posting rule may apply by
                // now). The claim is released; nothing was posted under it. If its reversal is still in
                // flight, that save then finds the claim released and aborts with no state change, so
                // the release can never be followed by the change it guarded; a person re-issues it.
                val deal = load(intent.dealId)
                intents.refuse(
                    intent.idempotencyKey,
                    if (deal.state == DealState.REVERSED) UNPOSTED_REVERSAL else UNPOSTED_REVERSAL_ABANDONED,
                )
                deal
            } else {
                reverse(
                    intent.dealId,
                    requireNotNull(intent.reason) {
                        "reversal intent without a reason"
                    },
                    intent.actor,
                    intent.commandKey,
                )
            }
        PostingIntentAction.ACCRUE -> {
            // Re-post the day under its key; recording the journal completes the intent. Preparing the
            // day (the deal, the posting table) is told apart from posting it, so only the former can
            // escalate: a ledger outage is resumed however long it lasts.
            @Suppress("TooGenericExceptionCaught")
                val (deal, spec) = try {
                val d = load(intent.dealId)
                d to PostingRules.accrual(d, intent.valueDate)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // a cancellation is not a failed attempt, and must never escalate a healthy day
            } catch (e: Exception) {
                throw AccrualPreparationFailed(e)
            }
            if (spec == null) {
                intents.refuse(intent.idempotencyKey, "the posting table yields no accrual for ${intent.valueDate}")
            } else {
                postGuarded(spec, intent, "treasury ${deal.product} accrual ${intent.valueDate}")
            }
            deal
        }
    }

    private fun describe(e: Throwable): String =
        "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(MAX_ERROR_LENGTH)

    /**
     * #14220 review: a journal recorded for the deal after the reversal read [seen] and before its
     * intent existed (an accrual pass already past its per-day check). The reversal would not offset
     * it, so it does not post. A fresh intent is closed REFUSED (nothing reached the ledger; re-issue
     * the reversal); a resumed one stays OPEN for an operator, since an earlier attempt may have posted.
     */
    private suspend fun requireNoJournalSince(dealId: UUID, seen: List<LedgerJournalRef>, intent: PostingIntent) {
        val known = seen.map { it.idempotencyKey }.toSet()
        val since = deals.journals(dealId).filter { it.idempotencyKey !in known }
        if (since.isEmpty()) return
        val reason = "journal(s) ${since.joinToString { it.idempotencyKey }} recorded while the reversal was " +
            "being decided; re-issue the reversal"
        if (intent.fresh) intents.refuse(intent.idempotencyKey, reason)
        throw com.openbank.treasury.application.port.out.DealChangedException(reason)
    }

    private suspend fun limitCheck(deal: Deal): LimitCheck {
        val cp = counterparties.findById(deal.counterpartyId) ?: throw UnknownCounterpartyException(deal.counterpartyId)
        // One measure for the pre-trade check and the locked line query (which sums the stored figure).
        val terms = creditTerms(deal.tradeDate, deal.header.collateral?.securityIsin)
        val dealAmount = CreditExposure.of(deal, deal.tradeDate, terms)
        val consumes = CreditMeasure.of(deal.product) != CreditMeasure.NONE
        val exposure = if (consumes) deals.exposure(cp.id, deal.limitCurrency, deal.id) else BigDecimal.ZERO
        return LimitCheck.of(cp, deal, exposure, dealAmount)
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
        const val NOT_DELIVERED = "product %s is not delivered yet (ADR-0344 delivery phases)"
        const val MAX_KEY_LENGTH = 128
        const val MAX_ERROR_LENGTH = 1000
        const val DEFAULT_MAX_RESUME_HOURS = 4L
        const val DEFAULT_CLAIM_BATCH = 100
        const val MAX_ACCRUAL_PREPARATION_ATTEMPTS = 3
        const val UNPOSTED_REVERSAL = "reversal of an unsettled deal: no journal, claim released"
        const val UNPOSTED_REVERSAL_ABANDONED =
            "reversal of an unsettled deal did not commit: no journal, claim released"

        /** Marks an unposted reversal's claim key, `...:reversed:claim:<attempt>`: never replayed. */
        const val CLAIM_MARKER = ":claim:"

        /** The actor an accrual day's posting intent is recorded under. */
        val ACCRUAL_ACTOR = Actor("system:interest-accrual", com.openbank.treasury.domain.model.ActorType.SYSTEM)
        const val DEFAULT_GRACE_MINUTES = 2L
        val CLAIM_LEASE: Duration = Duration.ofMinutes(10)
        const val ESCALATED = "escalated: too old to resume as the stored actor; " +
            "re-issue the command through the API or close the intent"

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

/** An accrual day that cannot even be prepared (deal or posting table), before any ledger call. */
private class AccrualPreparationFailed(cause: Throwable) :
    IllegalStateException("accrual day could not be prepared: ${cause.message}", cause)
