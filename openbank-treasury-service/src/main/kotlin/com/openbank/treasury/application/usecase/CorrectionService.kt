// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.finance.cashflow.FixingSource
import com.openbank.treasury.application.port.out.AccountingDayClosedException
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CorrectionCommit
import com.openbank.treasury.application.port.out.CorrectionRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealNotFoundException
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.ExposureGuard
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.MarketDataRepository
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.RateSource
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.CashFlowGenerated
import com.openbank.treasury.domain.model.CorrectionDelta
import com.openbank.treasury.domain.model.CorrectionPreview
import com.openbank.treasury.domain.model.CorrectionStaleException
import com.openbank.treasury.domain.model.CorrectionStatus
import com.openbank.treasury.domain.model.CorrectionTerms
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealCorrected
import com.openbank.treasury.domain.model.DealCorrection
import com.openbank.treasury.domain.model.DeltaJournal
import com.openbank.treasury.domain.model.FullUnwindPinning
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LedgerFlowItem
import com.openbank.treasury.domain.model.LimitBreachedException
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.LimitOverride
import com.openbank.treasury.domain.model.PostedDelta
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.RecordedJournal
import com.openbank.treasury.domain.reference.Ifrs9Category
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** No such correction on the deal: 404. */
class CorrectionNotFoundException(message: String) : NoSuchElementException(message)

/** A correction as stored, with the preview its proposer and reviewer saw (JSON). */
data class CorrectionView(val correction: DealCorrection, val preview: String?)

/** One pass over APPROVED corrections that still owe delta journals (the CORRECTIONS day step). */
data class CorrectionRun(val posted: Int, val journals: Int, val failures: List<Throwable>)

/** A delta journal as stored with the approved correction (what it committed to post). */
data class StoredDelta(
    val sourceEvent: PostingEvent,
    val key: String,
    val valueDate: LocalDate,
    val suffix: String,
    val lines: List<PostingLine>,
)

/**
 * ADR-0344 D7 corrections. Everything that decides lives on [DealCorrection] (four-eyes),
 * [Deal.correct] (the new terms version) and [CorrectionDelta] (the journals); this class loads,
 * re-derives and commits.
 *
 * Approval order is deliberate and the reverse of a settlement's: the new version, its schedule and
 * the payment withdrawal commit FIRST (one transaction under the deal lock, so a payment release or
 * a fixing racing it either lands first and is seen, or waits and is refused as stale); the delta
 * journals are posted AFTERWARDS under idempotent keys and the correction moves APPROVED → POSTED.
 * A crash in between leaves an APPROVED correction whose stored journals the next CORRECTIONS day
 * step posts — never a ledger entry with no committed correction behind it.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class CorrectionService(
    private val deals: DealRepository,
    private val corrections: CorrectionRepository,
    private val cashFlows: CashFlowRepository,
    private val schedules: ScheduleService,
    private val marketData: MarketDataRepository,
    private val ledger: LedgerPostingPort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val bookCategory: suspend (bookId: String?) -> Ifrs9Category = { Ifrs9Category.AMORTISED_COST },
    private val newId: () -> UUID = { Ids.newId() },
    /**
     * The booking path's own term vetting (TreasuryDealUseCase.vetCorrection): references, FX flag,
     * product mandate, counterparty limit under an optional senior override. Required: a correction
     * that skipped it could raise a notional past every limit the booking enforced.
     */
    private val vet: suspend (Deal, LimitOverride?) -> Pair<Deal, LimitCheck?>,
) {
    /** What one correction would commit, computed from the deal as it stands. */
    private data class Plan(
        val preview: CorrectionPreview,
        val schedule: GeneratedSchedule?,
        val changedFlowIds: Set<UUID>,
    )

    @Suppress("LongParameterList")
    suspend fun propose(
        dealId: UUID,
        terms: CorrectionTerms,
        fullUnwind: Boolean,
        reason: String,
        effectiveFrom: LocalDate,
        actor: Actor,
        key: String,
        requestLimitOverride: Boolean = false,
    ): CorrectionView {
        replay(key, PROPOSE, dealId)?.let { return it }
        val deal = load(dealId)
        if (corrections.forDeal(dealId).any { it.status == CorrectionStatus.PENDING }) {
            throw CorrectionStaleException("deal $dealId already has a PENDING correction: decide it first")
        }
        val c = DealCorrection.propose(
            newId(),
            deal,
            terms,
            fullUnwind,
            reason,
            effectiveFrom,
            actor,
            clock.instant(),
            requestLimitOverride,
        )
        if (!fullUnwind) {
            try {
                vet(deal.withCorrectedTerms(terms), null)
            } catch (e: LimitBreachedException) {
                // A counterparty-limit breach is proposable only as a request for a senior override;
                // a product-mandate breach is never (it is not overridable) and propagates as is.
                if (!requestLimitOverride) throw e
            }
        }
        val plan = plan(deal, c)
        val preview = previewJson(plan.preview)
        corrections.propose(c, preview, CommandKey(key, "$PROPOSE:${c.correctionId}", dealId))
        return CorrectionView(c, preview)
    }

    suspend fun approve(dealId: UUID, correctionId: UUID, actor: Actor, key: String): CorrectionView {
        replay(key, APPROVE, dealId)?.let { return it }
        val deal = load(dealId)
        val c = find(dealId, correctionId)
        val approved = c.approve(actor, deal, priorAuthors(dealId), clock.instant())
        // Read BEFORE the plan: if any write lands on the base schedule after this, the locked
        // commit sees a different fingerprint and refuses (409) instead of committing a stale plan.
        val fingerprint = corrections.fingerprint(deal.id, deal.scheduleVersion)
        val plan = plan(deal, c)
        val raw = deal.correct(c.terms, c.fullUnwind, actor, c.reason, clock.instant())
        // Re-vetted NOW (exposure and the mandate may have moved since the proposal); a full unwind
        // only reduces exposure. The counterparty headroom is re-measured once more under the lock.
        val (vetted, check) = if (c.fullUnwind) raw to null else vet(raw, c.limitOverride)
        val corrected = plan.schedule?.let { vetted.withSchedule(it.write.scheduleVersion, it.hash) } ?: vetted
        // The line is the deal's LIMIT currency (CZK for FX), measured by its limit amount: the same
        // figures LimitCheck used, the same key and query every booking takes.
        val guard = check?.let { ExposureGuard.of(it, c.limitOverride?.coversExposureUpTo) }
        // A full unwind is a reversal (#13411): pinned per GL account to what was posted, never re-resolved.
        val journalsToPost = if (c.fullUnwind) {
            pinnedUnwind(
                deal,
                plan.preview.deltaJournals,
            )
        } else {
            plan.preview.deltaJournals
        }
        val deltas = journalsToPost.map {
            StoredDelta(it.sourceEvent, it.key, it.valueDate, checkNotNull(it.spec.suffix), it.spec.lines)
        }
        corrections.approve(
            CorrectionCommit(
                approved,
                corrected,
                plan.schedule?.write,
                fingerprint,
                plan.changedFlowIds,
                events(approved, deal, corrected, plan),
                CommandKey(key, "$APPROVE:$correctionId", dealId),
                objectMapper.writeValueAsString(deltas),
                guard,
            ),
        )
        // Best effort now; an APPROVED correction is finished by the CORRECTIONS day step otherwise.
        val outcome = runCatching { post(approved, corrected, deltas) }.getOrNull()
        return CorrectionView(outcome ?: approved, corrections.preview(correctionId))
    }

    /** A senior's override of the counterparty-limit breach a requested correction causes. */
    suspend fun overrideLimit(
        dealId: UUID,
        correctionId: UUID,
        reason: String,
        actor: Actor,
        key: String,
    ): CorrectionView {
        replay(key, OVERRIDE, dealId)?.let { return it }
        val deal = load(dealId)
        val c = find(dealId, correctionId)
        val check = try {
            vet(deal.withCorrectedTerms(c.terms), null).second
        } catch (e: LimitBreachedException) {
            e.check
        } ?: error("the corrected deal consumes no counterparty limit")
        val overridden = c.overrideLimit(actor, deal, priorAuthors(dealId), reason, check, clock.instant())
        corrections.decide(overridden, CommandKey(key, "$OVERRIDE:$correctionId", dealId))
        return CorrectionView(overridden, corrections.preview(correctionId))
    }

    suspend fun reject(dealId: UUID, correctionId: UUID, reason: String, actor: Actor, key: String): CorrectionView {
        replay(key, REJECT, dealId)?.let { return it }
        val deal = load(dealId)
        val rejected = find(dealId, correctionId).reject(actor, deal, priorAuthors(dealId), reason, clock.instant())
        corrections.decide(rejected, CommandKey(key, "$REJECT:$correctionId", dealId))
        return CorrectionView(rejected, corrections.preview(correctionId))
    }

    suspend fun list(dealId: UUID): List<CorrectionView> {
        load(dealId)
        return corrections.forDeal(dealId).map { CorrectionView(it, corrections.preview(it.correctionId)) }
    }

    suspend fun get(dealId: UUID, correctionId: UUID): CorrectionView =
        CorrectionView(find(dealId, correctionId), corrections.preview(correctionId))

    suspend fun queue(status: CorrectionStatus, limit: Int): List<DealCorrection> {
        require(limit in 1..MAX_QUEUE) { "limit must be 1..$MAX_QUEUE" }
        return corrections.byStatus(status, limit)
    }

    /** Post the stored delta journals of every APPROVED correction (the CORRECTIONS day step). */
    suspend fun postPending(): CorrectionRun {
        var posted = 0
        var journals = 0
        val failures = mutableListOf<Throwable>()
        corrections.byStatus(CorrectionStatus.APPROVED, MAX_QUEUE).forEach { c ->
            runCatching {
                val deal = load(c.dealId)
                val stored: List<StoredDelta> = objectMapper.readValue(checkNotNull(corrections.deltas(c.correctionId)))
                post(c, deal, stored)
                stored.size
            }.onSuccess {
                posted++
                journals += it
            }.onFailure { failures += it }
        }
        return CorrectionRun(posted, journals, failures)
    }

    /**
     * Each delta journal posts on its own effective date (never before [DealCorrection.effectiveFrom],
     * never after today). A day the ledger has closed refuses with 409 ACCOUNTING_DAY_CLOSED: the
     * journal then posts into TODAY's open day and records the date it belonged to.
     */
    private suspend fun post(c: DealCorrection, deal: Deal, deltas: List<StoredDelta>): DealCorrection {
        val today = AccountingClock.bank(clock).today()
        val refs = mutableListOf<LedgerJournalRef>()
        val landed = deltas.map { d ->
            val spec = JournalSpec(c.dealId, PostingEvent.CORRECTION, d.lines, suffix = d.suffix)
            val wanted = minOf(maxOf(c.effectiveFrom, d.valueDate), today)
            val description = "treasury ${deal.product} correction ${d.sourceEvent.key} ${d.key}"
            val (journal, entry) = try {
                ledger.post(spec, wanted, description) to wanted
            } catch (e: AccountingDayClosedException) {
                if (wanted == today) throw e
                ledger.post(spec, today, "$description (value date $wanted)") to today
            }
            refs +=
                LedgerJournalRef(
                    c.dealId,
                    PostingEvent.CORRECTION,
                    spec.idempotencyKey,
                    journal.journalId,
                    clock.instant(),
                    journal.accounts,
                )
            PostedDelta(spec.idempotencyKey, journal.journalId, entry, wanted.takeIf { it != entry })
        }
        val done = c.posted(landed)
        corrections.markPosted(done, refs)
        return done
    }

    /**
     * The full unwind's delta journals, each offsetting per GL account exactly what its slot posted
     * ([FullUnwindPinning]): the original journal the daily run posted plus any earlier correction
     * journals of the slot that reached the ledger. Refused (409) when a journal's accounts are unknown.
     */
    private suspend fun pinnedUnwind(deal: Deal, deltas: List<DeltaJournal>): List<DeltaJournal> {
        val base = cashFlows.flows(deal.id, deal.scheduleVersion)
        val posted = CorrectionDelta.postedSpecs(deal, base, bookCategory(deal.bookId), reached(deal, base))
        val recorded = deals.journals(deal.id).associate {
            it.idempotencyKey to
                RecordedJournal(it.journalId, it.accounts)
        }
        val prior = corrections.forDeal(deal.id).flatMap { p ->
            corrections.deltas(p.correctionId)?.let { objectMapper.readValue<List<StoredDelta>>(it) }.orEmpty()
                .map { d -> d to JournalSpec(deal.id, PostingEvent.CORRECTION, d.lines, suffix = d.suffix) }
        }.filter { (_, spec) -> spec.idempotencyKey in recorded }
        return deltas.map { d ->
            val slot = Triple(d.sourceEvent, d.key, d.valueDate)
            val priorSpecs = prior.filter { (s, _) ->
                Triple(s.sourceEvent, s.key, s.valueDate) == slot
            }.map { it.second }
            d.copy(spec = FullUnwindPinning.pin(d.spec, posted[slot].orEmpty(), priorSpecs, recorded))
        }
    }

    private suspend fun plan(deal: Deal, c: DealCorrection): Plan {
        val base = cashFlows.flows(deal.id, deal.scheduleVersion)
        val category = bookCategory(deal.bookId)
        val reached = reached(deal, base)
        if (c.fullUnwind) {
            val deltas = CorrectionDelta.compute(c.correctionId, deal, base, null, emptyList(), category, reached)
            return Plan(
                CorrectionPreview(emptyList(), CorrectionDelta.changes(base, emptyList()), deltas),
                null,
                base.map { it.flowId }.toSet(),
            )
        }
        val target = deal.withCorrectedTerms(c.terms)
        schedules.requireEffective(target)
        val generated = schedules.generate(target, storedFixings(deal, base))
        val flows = restore(generated.flows, base)
        val changes = CorrectionDelta.changes(base, flows)
        val deltas = CorrectionDelta.compute(c.correctionId, deal, base, target, flows, category, reached)
        require(deltas.size <= DealCorrection.MAX_DELTA_JOURNALS) { "the correction implies too many journals" }
        return Plan(
            CorrectionPreview(flows, changes, deltas),
            generated.copy(flows = flows, write = generated.write.copy(flows = flows)),
            changes.mapNotNull { it.before?.flowId }.toSet(),
        )
    }

    /**
     * The fixings the base schedule already observed, re-supplied by value so the engine reproduces
     * them: looked up by the stored provenance hash, never re-read as "whatever is active today".
     */
    private suspend fun storedFixings(deal: Deal, base: List<CashFlow>): FixingSource {
        val values = mutableMapOf<Pair<String, LocalDate>, java.math.BigDecimal>()
        base.filter { it.rateSource == RateSource.OBSERVED && it.fixingSha256 != null && it.fixingDate != null }
            .forEach { f ->
                val index = deal.legs.getOrNull(f.legIndex)?.rate?.index ?: return@forEach
                val date = checkNotNull(f.fixingDate)
                if (index to date in values) return@forEach
                marketData.active(index, date, date).firstOrNull { it.sha256 == f.fixingSha256 }
                    ?.let { values[index to date] = it.value }
            }
        return FixingSource.of(values)
    }

    /**
     * Unchanged FIXED periods stay FIXED with the SAME provenance (source, hash, fixed-at); a flow of
     * a payment keeps its link (a withdrawn one is unlinked in the commit) and a settled one its state.
     */
    private fun restore(generated: List<CashFlow>, base: List<CashFlow>): List<CashFlow> {
        val bySlot = base.associateBy { it.slot }
        return generated.map { f ->
            val b = bySlot[f.slot] ?: return@map f
            var out = f
            if (f.rateSource == RateSource.OBSERVED && b.fixingSha256 != null && b.fixingDate == f.fixingDate) {
                out = out.copy(
                    state = if (out.state == CashFlowState.PROJECTED) CashFlowState.FIXED else out.state,
                    fixingSource = b.fixingSource,
                    fixingSha256 = b.fixingSha256,
                    fixedAt = b.fixedAt,
                )
            }
            if (b.paymentId != null) {
                out = out.copy(
                    paymentId = b.paymentId,
                    state = if (b.state == CashFlowState.SETTLED && out.amount != null) b.state else out.state,
                )
            }
            out
        }
    }

    private suspend fun reached(deal: Deal, base: List<CashFlow>): CorrectionDelta.Reached {
        val journals = deals.journals(deal.id)
        val lastAccrual = journals.filter { it.event == PostingEvent.ACCRUAL }
            .maxOfOrNull { LocalDate.parse(it.idempotencyKey.substringAfterLast(':')) }
        val paid = journals.filter { it.event == PostingEvent.PAYMENT || it.event == PostingEvent.AMORTISATION }
            .mapNotNull { j -> runCatching { UUID.fromString(j.idempotencyKey.substringAfterLast(':')) }.getOrNull() }
            .toSet()
        val lastPaid = base.filter { it.flowId in paid }.maxOfOrNull { it.paymentDate }
        return CorrectionDelta.Reached(
            trade = journals.any { it.event == PostingEvent.TRADE },
            settlement = journals.any { it.event == PostingEvent.SETTLEMENT },
            lastAccrual = listOfNotNull(lastAccrual, lastPaid).maxOrNull(),
            maturity = journals.any { it.event == PostingEvent.MATURITY },
        )
    }

    private fun events(c: DealCorrection, from: Deal, to: Deal, plan: Plan): List<DealEvent> = buildList {
        add(
            DealEvent(
                DealCorrected.EVENT_TYPE,
                objectMapper.writeValueAsString(
                    DealCorrected(
                        dealId = to.id,
                        correctionId = c.correctionId,
                        product = to.product,
                        fromVersion = from.version,
                        toVersion = to.version,
                        scheduleVersion = to.scheduleVersion,
                        fullUnwind = c.fullUnwind,
                        changedFlows = plan.preview.changed.size,
                        deltaJournals = plan.preview.deltaJournals.size,
                        effectiveFrom = c.effectiveFrom,
                        reason = c.reason,
                        proposedBy = c.proposedBy.id,
                        approvedBy = checkNotNull(c.decidedBy).id,
                        occurredAt = checkNotNull(c.decidedAt),
                    ),
                ),
            ),
        )
        plan.schedule?.let { g ->
            add(
                DealEvent(
                    CashFlowGenerated.EVENT_TYPE,
                    objectMapper.writeValueAsString(
                        CashFlowGenerated(
                            dealId = to.id,
                            product = to.product,
                            kind = to.kind,
                            bookId = to.bookId,
                            dealVersion = to.version,
                            scheduleVersion = to.scheduleVersion,
                            scheduleHash = g.hash,
                            flowCount = g.flows.size,
                            projectedCount = g.flows.count { it.amount == null },
                            carriedOverrides = g.write.carried.size,
                            conflicts = g.write.conflicts.size,
                            occurredAt = checkNotNull(c.decidedAt),
                            flows = g.flows.map(LedgerFlowItem::of),
                        ),
                    ),
                ),
            )
        }
    }

    private fun previewJson(p: CorrectionPreview): String = objectMapper.writeValueAsString(
        mapOf(
            "newFlows" to p.newFlows.map(LedgerFlowItem::of),
            "changedFlows" to p.changed.map { ch ->
                mapOf(
                    "slot" to CorrectionDelta.slotKey(ch.slot),
                    "before" to ch.before?.let(LedgerFlowItem::of),
                    "after" to ch.after?.let(LedgerFlowItem::of),
                )
            },
            "deltaJournals" to p.deltaJournals.map(::deltaView),
        ),
    )

    private fun deltaView(d: DeltaJournal) = mapOf(
        "sourceEvent" to d.sourceEvent.name,
        "key" to d.key,
        "valueDate" to d.valueDate,
        "idempotencyKey" to d.spec.idempotencyKey,
        "lines" to d.spec.lines.map {
            mapOf(
                "accountingClass" to it.accountingClass,
                "role" to it.role.name,
                "side" to it.side.name,
                "amount" to it.amount,
                "currency" to it.currency,
            )
        },
    )

    private suspend fun priorAuthors(dealId: UUID): Set<String> = corrections.forDeal(dealId)
        .filter { it.status == CorrectionStatus.APPROVED || it.status == CorrectionStatus.POSTED }
        .map { it.proposedBy.id }.toSet()

    private suspend fun load(dealId: UUID): Deal = deals.findById(dealId) ?: throw DealNotFoundException(dealId)

    private suspend fun find(dealId: UUID, correctionId: UUID): DealCorrection =
        corrections.find(correctionId)?.takeIf { it.dealId == dealId }
            ?: throw CorrectionNotFoundException("correction $correctionId not found on deal $dealId")

    /** A replayed key answers with the correction as it now stands; a key reused elsewhere is refused. */
    private suspend fun replay(key: String, action: String, dealId: UUID): CorrectionView? {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) {
            "Idempotency-Key must be 1..$MAX_KEY_LENGTH characters"
        }
        val prior = deals.findCommand(key) ?: return null
        require(prior.action.startsWith("$action:") && prior.dealId == dealId) {
            "Idempotency-Key was already used for ${prior.action} on another request"
        }
        val id = UUID.fromString(prior.action.substringAfter(':'))
        return CorrectionView(
            checkNotNull(corrections.find(id)) { "correction $id disappeared" },
            corrections.preview(id),
        )
    }

    private companion object {
        const val MAX_KEY_LENGTH = 128
        const val MAX_QUEUE = 500
        const val PROPOSE = "CORRECTION_PROPOSE"
        const val APPROVE = "CORRECTION_APPROVE"
        const val REJECT = "CORRECTION_REJECT"
        const val OVERRIDE = "CORRECTION_OVERRIDE_LIMIT"
    }
}
