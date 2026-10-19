// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.application.port.out.PaymentNotFoundException
import com.openbank.treasury.application.port.out.PaymentRepository
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.domain.payment.FundingCheck
import com.openbank.treasury.domain.payment.FundingExceededException
import com.openbank.treasury.domain.payment.Payment
import com.openbank.treasury.domain.payment.PaymentProposals
import com.openbank.treasury.domain.payment.PaymentStaleException
import com.openbank.treasury.domain.payment.PaymentStatus
import com.openbank.treasury.domain.payment.ProposalOutcome
import com.openbank.treasury.domain.payment.RailStatus
import com.openbank.treasury.domain.payment.RailStatusConflictException
import com.openbank.treasury.domain.payment.SsiSnapshot
import com.openbank.treasury.domain.reference.NettingPayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceStatus
import com.openbank.treasury.domain.reference.SsiPayload
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ProposalRun(
    val date: LocalDate,
    val proposed: List<Payment>,
    val nettedToZero: Int,
    val unaddressable: List<String>,
    val failures: List<Throwable>,
)

/** A payment status change as `treasury.payment.status-changed.v1` carries it (ADR-0344 D6). */
data class PaymentStatusChanged(
    val paymentId: UUID,
    val status: String,
    val direction: String,
    val counterpartyId: String,
    val currency: String,
    val amount: BigDecimal,
    val valueDate: LocalDate,
    val rail: String?,
    val railReference: String?,
    val nettedFlowIds: List<UUID>,
    val dealIds: List<UUID>,
    val actor: String?,
    val reason: String?,
    val occurredAt: Instant,
    val sourceService: String = "treasury-service",
) {
    companion object {
        const val EVENT_TYPE = "treasury.payment.status-changed.v1"
    }
}

/** Contract 9.1: one party of an institution transfer. */
data class TransferParty(
    val bic: String,
    val accountNumber: String,
    val glAccountingClass: String? = null,
    val name: String? = null,
)

/** Contract 9.1: `treasury.payment.released.v1` on `openbank.treasury.payments`, written in the release transaction. */
data class PaymentReleased(
    val paymentId: UUID,
    val idempotencyKey: String,
    val rail: String,
    val currency: String,
    val amount: String,
    val valueDate: LocalDate,
    val debtor: TransferParty,
    val creditor: TransferParty,
    val intermediaryBic: String?,
    /** The SSI version the creditor was addressed from (re-verified inside the release transaction). */
    val ssiVersion: Int,
    val remittanceInformation: String,
    val dealIds: List<UUID>,
    val flowIds: List<UUID>,
    val releasedBy: String,
    val releasedAt: Instant,
    val synthetic: Boolean,
) {
    companion object {
        const val EVENT_TYPE = "treasury.payment.released.v1"
        const val MAX_TEXT = 140
    }
}

/** Contract 9.2: `swift.institution-transfer.status.v1` as treasury reads it. */
data class InstitutionTransferStatus(
    val paymentId: UUID,
    val idempotencyKey: String,
    val status: RailStatus,
    val messageType: String?,
    val schemeReference: String?,
    val reasonCode: String?,
    val reason: String?,
    val at: Instant?,
    val synthetic: Boolean = false,
)

/** Who the counterparty is for the released event's creditor name and synthetic taint (ADR-0252). */
data class CounterpartyIdentity(val name: String, val synthetic: Boolean)

/**
 * ADR-0344 D6: the deal-to-payment chain. FIXED flows inside the cut-off window become PROPOSED
 * payments (netted per netting agreement, addressed from the effective SSI); a human payment
 * releaser who is neither a deal's creator nor a term author releases one; the funding account
 * must not go negative as of the value date (#12115). Contract 9: the release is ASYNC and ATOMIC —
 * the RELEASED row and `treasury.payment.released.v1` commit in one transaction (no synchronous rail
 * call, no crash window between them); the rail's status events then move it to SENT / SETTLED /
 * FAILED ([onRailStatus]).
 * Incoming flows are expected receipts that a nostro statement entry settles.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class PaymentService(
    private val payments: PaymentRepository,
    private val deals: DealRepository,
    private val cashFlows: CashFlowRepository,
    private val references: ReferenceRepository,
    private val ledger: LedgerReadPort?,
    private val dayCompletion: DayCompletionRepository?,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val cutOffWindowDays: Long,
    private val onFundingExceeded: (String) -> Unit = {},
    private val onParkedMatch: (UUID) -> Unit = {},
    private val newId: () -> UUID = { Ids.newId() },
    /** Contract 9.1 debtor agent: the bank's own BIC. */
    private val ownBic: String = DEFAULT_OWN_BIC,
    private val counterparty: suspend (String) -> CounterpartyIdentity? = { null },
    /** Step 2 (ADR-0344 D7): posts the PAYMENT journals of a SENT/SETTLED payment's flows; returns journals posted. */
    private val postOutflow: suspend (Payment) -> Int = { 0 },
    private val onOutflowFailed: (UUID, Throwable) -> Unit = { _, _ -> },
) {
    init {
        require(cutOffWindowDays in 0..MAX_WINDOW) { "the payment cut-off window is 0..$MAX_WINDOW days" }
    }

    /** Propose payments for every unpaid FIXED flow paid in [date]..[date + window]. Records the PAYMENTS step. */
    suspend fun propose(date: LocalDate): ProposalRun {
        val flows = payments.unpaidFixedFlows(date, date.plusDays(cutOffWindowDays))
        val failures = mutableListOf<Throwable>()
        val candidates = flows.groupBy { it.dealId }.flatMap { (dealId, fs) ->
            val deal = deals.findById(dealId) ?: return@flatMap emptyList()
            val authors = termAuthors(dealId) + listOfNotNull(deal.createdBy.id, deal.submittedBy?.id)
            fs.map { PaymentProposals.Candidate(it, deal.counterpartyId, authors.toSet()) }
        }
        val pairs = candidates.map { it.counterpartyId to it.flow.currency }.distinct()
        val nets = pairs.associateWith { (cp, ccy) -> nettingOn(cp, ccy, date) }
        val ssis = pairs.associateWith { (cp, ccy) -> ssiOn(cp, ccy, date) }
        val outcomes = PaymentProposals.propose(
            candidates,
            netting = { cp, ccy -> nets[cp to ccy] == true },
            ssi = { cp, ccy -> ssis[cp to ccy] },
            newId = newId,
            at = clock.instant(),
        )
        val proposed = mutableListOf<Payment>()
        outcomes.filterIsInstance<ProposalOutcome.Proposed>().forEach { (p) ->
            runCatching {
                if (payments.findByKey(p.idempotencyKey) == null) {
                    payments.save(p, null, event(p, null, null))
                    proposed += p
                }
            }.onFailure { failures += it }
        }
        val unaddressable = outcomes.filterIsInstance<ProposalOutcome.Unaddressable>()
            .map { "${it.counterpartyId}/${it.currency}: no effective SSI for ${it.flowIds.size} flow(s)" }
        dayCompletion?.record(
            DayStepRecord.of(
                date,
                DayStep.PAYMENTS,
                proposed.size,
                failures.size + unaddressable.size,
                clock.instant(),
                journals = 0,
            )
                .let { r ->
                    if (unaddressable.isNotEmpty() &&
                        failures.isEmpty()
                    ) {
                        r.copy(detail = unaddressable.joinToString("; ").take(DayStepRecord.MAX_DETAIL))
                    } else {
                        r
                    }
                },
        )
        return ProposalRun(
            date,
            proposed,
            outcomes.count {
                it is ProposalOutcome.NettedToZero
            },
            unaddressable,
            failures,
        )
    }

    suspend fun release(paymentId: UUID, actor: Actor): Payment {
        val current = payments.find(paymentId) ?: throw PaymentNotFoundException(paymentId)
        // ADR-0344 D6 four-eyes, computed NOW: every term author of every netted deal and every
        // proposer and decider of an override on a netted flow; never the proposal-time cache.
        val released = current.release(actor, clock.instant(), authorsOf(current))
        val nostro = ledger?.takeIf {
            released.direction == FlowDirection.OUT
        }?.nostroAccounts()?.get(released.currency)
        val (key, check) = funding(released, nostro)
        val events = listOfNotNull(
            event(released, actor.id, released.statusReason),
            released.transferRail?.let { releasedEvent(released, nostro?.glCode) },
        )
        payments.saveRelease(released, events, key, check)
        return released
    }

    /**
     * Contract 9.2, idempotent on (paymentId, status): ACCEPTED records the rail reference, SENT ->
     * SENT (and posts the outflow journals), SETTLED -> SETTLED with its flows, REJECTED -> FAILED
     * (flows stay linked, the funding reservation is released because FAILED no longer counts as an
     * outflow). A replay or a status the payment is already past stores nothing. A concurrent status
     * on the same payment loses the row lock race, reloads and re-decides. An unknown payment, a key
     * that is not the payment's own, or a contradicting status is thrown — the consumer's DLQ parks
     * it for a human, never guessed at.
     */
    suspend fun onRailStatus(s: InstitutionTransferStatus): Payment? {
        var attempt = 0
        while (true) {
            val current = railTarget(s)
            val reason = listOfNotNull(s.reasonCode, s.reason).joinToString(": ").ifBlank { null }
            val next = current.onRail(s.status, s.schemeReference, reason)
            if (next == null) {
                // A replay of SENT/SETTLED still makes sure the outflow journals exist (idempotent keys).
                if (current.status == PaymentStatus.SENT || current.status == PaymentStatus.SETTLED) {
                    outflow(current)
                }
                return null
            }
            try {
                payments.save(
                    next,
                    current.status,
                    event(next, RAIL_ACTOR, reason),
                    settleFlows = next.status == PaymentStatus.SETTLED,
                )
            } catch (e: PaymentStaleException) {
                if (++attempt >= MAX_STATUS_ATTEMPTS) throw e
                continue
            }
            if (next.status == PaymentStatus.SENT || next.status == PaymentStatus.SETTLED) outflow(next)
            return next
        }
    }

    /**
     * The status is already committed; a ledger outage here must not dead-letter it. The same keys
     * are posted by the next replay and, at the latest, by the daily matrix run on the payment date;
     * until then the funding check still counts the flows as unbooked, so nothing is overdrawn.
     */
    private suspend fun outflow(p: Payment) {
        runCatching { postOutflow(p) }.onFailure { onOutflowFailed(p.paymentId, it) }
    }

    private suspend fun railTarget(s: InstitutionTransferStatus): Payment {
        val current = payments.find(s.paymentId) ?: throw PaymentNotFoundException(s.paymentId)
        if (current.idempotencyKey != s.idempotencyKey) {
            throw RailStatusConflictException(
                "status for payment ${s.paymentId} carries key ${s.idempotencyKey.take(KEY_ECHO)}, not its own",
            )
        }
        return current
    }

    private suspend fun releasedEvent(p: Payment, nostroAccount: String?): DealEvent {
        val ssi = checkNotNull(p.ssi) { "an outgoing payment is addressed from an SSI" }
        val cp = counterparty(p.counterpartyId)
        return DealEvent(
            PaymentReleased.EVENT_TYPE,
            objectMapper.writeValueAsString(
                PaymentReleased(
                    paymentId = p.paymentId,
                    idempotencyKey = p.idempotencyKey,
                    rail = checkNotNull(p.transferRail).name,
                    currency = p.currency,
                    amount = p.amount.setScale(2).toPlainString(),
                    valueDate = p.valueDate,
                    debtor = TransferParty(
                        bic = ownBic,
                        accountNumber = nostroAccount ?: "NOSTRO-${p.currency}",
                        glAccountingClass = com.openbank.treasury.domain.model.AccountingClasses.NOSTRO,
                    ),
                    creditor = TransferParty(
                        bic = checkNotNull(ssi.bic),
                        accountNumber = ssi.beneficiaryAccount,
                        name = (cp?.name ?: p.counterpartyId).take(PaymentReleased.MAX_TEXT),
                    ),
                    intermediaryBic = ssi.intermediaryBic,
                    ssiVersion = ssi.ssiVersion,
                    remittanceInformation = ("TREASURY " + p.dealIds.joinToString(" ")).take(PaymentReleased.MAX_TEXT),
                    dealIds = p.dealIds,
                    flowIds = p.nettedFlowIds,
                    releasedBy = checkNotNull(p.releasedBy),
                    releasedAt = checkNotNull(p.releasedAt),
                    synthetic = cp?.synthetic ?: true,
                ),
            ),
        )
    }

    /** A released payment ends MANUALLY_SETTLED (flows SETTLED) or RETURNED, by an independent human. */
    suspend fun resolve(paymentId: UUID, outcome: PaymentStatus, reason: String, actor: Actor): Payment {
        val current = payments.find(paymentId) ?: throw PaymentNotFoundException(paymentId)
        val resolved = current.resolve(actor, outcome, reason, authorsOf(current))
        payments.save(
            resolved,
            current.status,
            event(resolved, actor.id, reason),
            settleFlows = outcome == PaymentStatus.MANUALLY_SETTLED,
        )
        return resolved
    }

    suspend fun cancel(paymentId: UUID, reason: String, actor: Actor): Payment {
        val current = payments.find(paymentId) ?: throw PaymentNotFoundException(paymentId)
        val cancelled = current.cancel(actor, reason)
        payments.save(cancelled, current.status, event(cancelled, actor.id, reason))
        return cancelled
    }

    private suspend fun authorsOf(p: Payment): Set<String> = p.dealIds.flatMap { dealId ->
        val deal = deals.findById(dealId)
        val overrides = cashFlows.overrides(dealId).filter { it.flowId in p.nettedFlowIds }
        termAuthors(dealId) + listOfNotNull(deal?.createdBy?.id, deal?.submittedBy?.id) +
            overrides.map { it.proposedBy.id } + overrides.mapNotNull { it.decidedBy?.id }
    }.toSet()

    /**
     * A credit on the configured nostro of [currency] (the upload already refused any other account)
     * of [amount] on [valueDate]. It settles an expected receipt only when it identifies exactly one:
     * by [reference] (the payment id or its key) or, failing that, as the single IN payment of that
     * amount and date. An ambiguous or unknown credit settles nothing. The [uploader] must be
     * independent of the payment — never a term author or its releaser — or the match is parked
     * (left PROPOSED, reported) for an independent upload. Returns the settled payment or null.
     */
    suspend fun matchIncoming(
        currency: String,
        amount: BigDecimal,
        valueDate: LocalDate,
        reference: String,
        uploader: Actor,
    ): Payment? {
        val candidates = payments.expectedIn(currency, amount, valueDate)
        val byRef = candidates.filter { reference == it.paymentId.toString() || reference == it.idempotencyKey }
        val only = (byRef.singleOrNull() ?: candidates.singleOrNull()?.takeIf { byRef.isEmpty() }) ?: return null
        if (uploader.type != com.openbank.treasury.domain.model.ActorType.HUMAN ||
            uploader.id == only.releasedBy ||
            uploader.id in authorsOf(only)
        ) {
            onParkedMatch(only.paymentId)
            return null
        }
        val settled = only.settled(reference)
        payments.save(
            settled,
            only.status,
            event(settled, uploader.id, "matched nostro entry $reference"),
            settleFlows = true,
        )
        return settled
    }

    /**
     * Read-only pre-check before a deal change has side effects (e.g. posting a reversal journal):
     * a released payment refuses it (409). It withdraws NOTHING — the binding check and the
     * withdrawal of PROPOSED payments run under the deal lock in the change's own transaction
     * (DealRepository.save withdrawPayments).
     */
    suspend fun guardDealChange(dealId: UUID) {
        payments.forDeal(dealId).firstOrNull { dealId in it.dealIds && it.status.blocksDealChange }?.let {
            throw com.openbank.treasury.domain.payment.DealHasReleasedPaymentException(dealId, it.paymentId)
        }
    }

    suspend fun list(status: PaymentStatus?, from: LocalDate, to: LocalDate, limit: Int): List<Payment> {
        require(!to.isBefore(from)) { "from must not be after to" }
        return payments.list(status, from, to, limit)
    }

    suspend fun find(paymentId: UUID): Payment = payments.find(paymentId) ?: throw PaymentNotFoundException(paymentId)

    /**
     * #12115: the funding bucket key and the check run INSIDE the release transaction under its
     * advisory lock, so two releases that each fit but together overdraw cannot both pass.
     */
    private suspend fun funding(
        p: Payment,
        nostro: com.openbank.treasury.application.port.out.NostroGl?,
    ): Pair<String, (BigDecimal) -> Unit> {
        val port = ledger
        if (p.direction != FlowDirection.OUT || port == null) return "none" to { _ -> }
        nostro
            ?: throw FundingExceededException(
                FundingCheck.Result(BigDecimal.ZERO, p.amount.negate()),
                "no funding (nostro) account in ${p.currency}",
            )
        val balance = port.accountBalance(nostro.glCode, p.currency, p.valueDate) ?: BigDecimal.ZERO
        return "treasury-funding:${nostro.glCode}:${p.currency}:${p.valueDate}" to { pending ->
            val result = FundingCheck.check(balance, pending, p.amount)
            if (result.exceeded) {
                onFundingExceeded(p.currency)
                throw FundingExceededException(
                    result,
                    "funding account ${nostro.glCode} would be ${result.after.toPlainString()} ${p.currency} on ${p.valueDate}",
                )
            }
        }
    }

    private suspend fun termAuthors(dealId: UUID): List<String> = cashFlows.versions(dealId).map { it.createdBy }

    private suspend fun nettingOn(cp: String, ccy: String, on: LocalDate): Boolean =
        references.list(ReferenceKind.NETTING, ReferenceStatus.ACTIVE, on).map {
            it.payload
        }.filterIsInstance<NettingPayload>()
            .any { it.counterpartyId == cp && ccy in it.currencies && it.paymentNetting }

    private suspend fun ssiOn(cp: String, ccy: String, on: LocalDate): SsiSnapshot? =
        references.versions(ReferenceKind.SSI, "$cp:$ccy").lastOrNull { it.isEffectiveOn(on) }?.let { r ->
            val s = r.payload as SsiPayload
            SsiSnapshot(s.rail, s.beneficiaryAccount, s.bic, r.version, s.intermediaryBic)
        }

    private fun event(p: Payment, actor: String?, reason: String?) = DealEvent(
        PaymentStatusChanged.EVENT_TYPE,
        objectMapper.writeValueAsString(
            PaymentStatusChanged(
                paymentId = p.paymentId,
                status = p.status.name,
                direction = p.direction.name,
                counterpartyId = p.counterpartyId,
                currency = p.currency,
                amount = p.amount,
                valueDate = p.valueDate,
                rail = p.transferRail?.name ?: p.ssi?.rail?.name,
                railReference = p.railReference,
                nettedFlowIds = p.nettedFlowIds,
                dealIds = p.dealIds,
                actor = actor,
                reason = reason,
                occurredAt = clock.instant(),
            ),
        ),
    )

    companion object {
        const val MAX_WINDOW = 31L
        const val MAX_STATUS_ATTEMPTS = 3
        const val KEY_ECHO = 64
        const val RAIL_ACTOR = "rail:institution-transfer"
        const val DEFAULT_OWN_BIC = "OPBKCZ22"
    }
}
