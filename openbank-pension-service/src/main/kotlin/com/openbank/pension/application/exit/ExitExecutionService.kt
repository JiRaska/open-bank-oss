// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit

import com.openbank.pension.application.usecase.ParticipantNotices
import com.openbank.pension.domain.exit.DeathClaimStatus
import com.openbank.pension.domain.exit.InstallmentStatus
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.PayoutStatus
import com.openbank.pension.domain.exit.TerminationStatus
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.PensionContract
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The money-moving steps the exit workflows run as activities. Every step is IDEMPOTENT twice
 * over: it first reads the aggregate and returns early when the step already happened, and every
 * external instruction carries a deterministic key (`pension-<kind>-<aggregate id>-<step>`), so a
 * Temporal retry after a crash between "instructed" and "recorded" repeats the same instruction,
 * which the receiver deduplicates, and [PaymentInstructionRepository] never holds two rows for it.
 *
 * Amounts come ONLY from the stored binding quote (or the schedule cut from it) — never from a
 * fresh valuation — which is the preview == executed invariant.
 */
@Suppress("TooManyFunctions") // one function per workflow step; each must stay independently idempotent
class ExitExecutionService(private val ctx: ExitContext) {

    private val stores get() = ctx.stores
    private val gw get() = ctx.gateways
    private fun now() = ctx.clock.instant()

    // ---- early termination ----

    /** False when the notice was superseded by a death claim: the workflow stops. */
    suspend fun terminationRedeem(noticeId: UUID): Boolean {
        val notice = requireNotNull(stores.notices.findById(noticeId)) { "notice $noticeId vanished" }
        if (notice.status == TerminationStatus.SUPERSEDED) return false
        if (notice.status != TerminationStatus.SIGNED) return true
        if (stores.claims.findByContract(notice.contractId) != null) {
            stores.notices.save(notice.supersede(now()))
            return false
        }
        val proceeds = redeem(notice.contractId, notice.quote.redemptionValue, key("term", noticeId, "redeem"))
        stores.notices.save(notice.markRedeemed(proceeds, now()))
        return true
    }

    suspend fun terminationSettle(noticeId: UUID) {
        val notice = requireNotNull(stores.notices.findById(noticeId))
        if (notice.status != TerminationStatus.REDEEMED) return
        val q = notice.quote
        if (q.incentiveReturn.signum() > 0) {
            gw.incentives.settleClawback(notice.contractId, q.incentiveReturn, key("term", noticeId, "clawback"))
        }
        remit(notice.contractId, "DEDUCTION_RECAPTURE", q.deductionRecapture, key("term", noticeId, "tax-deduction"))
        remit(
            notice.contractId,
            "EMPLOYER_EXEMPT_RECAPTURE",
            q.employerExemptRecapture,
            key("term", noticeId, "tax-employer"),
        )
        val contract = contract(notice.contractId)
        val ref = pay(
            key("term", noticeId, "payout"),
            contract,
            "EARLY_TERMINATION",
            contract.participantPartyId.toString(),
            requireNotNull(notice.payoutIban),
            q.netPayout,
            q.currency,
        )
        stores.notices.save(notice.markPaid(ref, now()))
    }

    suspend fun terminationComplete(noticeId: UUID) {
        val notice = requireNotNull(stores.notices.findById(noticeId))
        if (notice.status != TerminationStatus.PAID) return
        val contract = contract(notice.contractId)
        if (contract.status == ContractStatus.TERMINATING) stores.contracts.save(contract.close(now()))
        stores.notices.save(notice.complete(now()))
    }

    // ---- regular payout ----

    /** The PENDING installments as (seq, due epoch day); empty for a single payment. */
    suspend fun payoutPlan(payoutId: UUID): List<Pair<Int, Long>> {
        val payout = payout(payoutId)
        return payout.schedule?.installments.orEmpty()
            .filter { it.status == InstallmentStatus.PENDING }
            .map { it.seq to it.dueDate.toEpochDay() }
    }

    suspend fun payoutSingle(payoutId: UUID) {
        var payout = payout(payoutId)
        if (payout.scheduled ||
            payout.status == PayoutStatus.COMPLETED ||
            payout.status == PayoutStatus.REVERSED
        ) {
            return
        }
        val q = payout.quote
        if (payout.status == PayoutStatus.CONFIRMED) {
            val proceeds = redeem(payout.contractId, q.grossAmount, key("payout", payoutId, "redeem"))
            payout = stores.payouts.save(payout.markRedeemed(proceeds, now()))
        }
        remit(payout.contractId, "PAYOUT_WITHHOLDING", q.taxWithheld, key("payout", payoutId, "tax"))
        val contract = contract(payout.contractId)
        if (payout.form == PayoutForm.ANNUITY) {
            if (payout.annuity == null && payout.paymentRef == null && payout.status == PayoutStatus.IN_PAYMENT) {
                placeAnnuity(payout, contract)
            }
        } else if (payout.paymentRef == null) {
            val ref = pay(
                key("payout", payoutId, "pay"),
                contract,
                payout.form.name,
                contract.participantPartyId.toString(),
                requireNotNull(payout.payoutIban),
                q.netAmount,
                q.currency,
            )
            stores.payouts.save(payout.markPaid(ref, now()))
        }
    }

    /** The premium goes to the selected partner (#12383); a failure is compensated per pack. */
    private suspend fun placeAnnuity(payout: PayoutRequest, contract: PensionContract) {
        when (val outcome = gw.annuities.place(payout, contract)) {
            is AnnuityPlacement.Issued -> stores.payouts.save(payout.markAnnuityPurchased(outcome.policy, now()))
            is AnnuityPlacement.ReturnedToClient -> stores.payouts.save(payout.markPaid(outcome.paymentRef, now()))
            AnnuityPlacement.ReturnedToContract -> {
                stores.payouts.save(payout.reverseAnnuity(now()))
                if (contract.status == ContractStatus.TERMINATING) {
                    stores.contracts.save(contract.reopenAfterReversedPayout(now()))
                }
            }
        }
    }

    /** False when a death claim froze the contract: remaining installments go to the claim. */
    suspend fun payoutInstallment(payoutId: UUID, seq: Int): Boolean {
        var payout = payout(payoutId)
        if (stores.claims.findByContract(payout.contractId) != null) return false
        payout = stores.payouts.save(payout.startInstallments(now()))
        val installment = requireNotNull(payout.schedule).installments.first { it.seq == seq }
        if (installment.status == InstallmentStatus.PAID) return true
        redeem(payout.contractId, installment.gross, key("payout", payoutId, "i$seq-redeem"))
        remit(payout.contractId, "PAYOUT_WITHHOLDING", installment.tax, key("payout", payoutId, "i$seq-tax"))
        val contract = contract(payout.contractId)
        val ref = pay(
            key("payout", payoutId, "i$seq-pay"),
            contract,
            "${payout.form.name}#$seq",
            contract.participantPartyId.toString(),
            // The signed account, or a held account change that has taken effect for this due date.
            requireNotNull(payout.accountFor(installment.dueDate, LocalDate.now(ctx.clock))),
            installment.net,
            payout.quote.currency,
        )
        stores.payouts.save(payout.markInstallmentPaid(seq, ref, now()))
        return true
    }

    suspend fun payoutComplete(payoutId: UUID) {
        val payout = payout(payoutId)
        if (payout.status == PayoutStatus.COMPLETED || payout.status == PayoutStatus.REVERSED) return
        stores.payouts.save(payout.complete(now()))
        if (!payout.partial) {
            val contract = contract(payout.contractId)
            if (contract.status == ContractStatus.TERMINATING) stores.contracts.save(contract.markPaidOut(now()))
        }
    }

    // ---- death ----

    suspend fun deathRedeem(claimId: UUID) {
        val claim = requireNotNull(stores.claims.findById(claimId))
        if (claim.status != DeathClaimStatus.APPROVED) return
        if (claim.incentiveReturn.signum() > 0) {
            gw.incentives.settleClawback(claim.contractId, claim.incentiveReturn, key("death", claimId, "clawback"))
        }
        val proceeds = redeem(
            claim.contractId,
            requireNotNull(claim.valuation),
            key("death", claimId, "redeem"),
        )
        stores.claims.save(claim.markRedeemed(proceeds, now()))
    }

    suspend fun deathClaimants(claimId: UUID): List<String> =
        requireNotNull(stores.claims.findById(claimId)).claimants.map { it.id.toString() }

    suspend fun deathPayClaimant(claimId: UUID, claimantId: UUID) {
        val claim = requireNotNull(stores.claims.findById(claimId))
        val claimant = claim.claimant(claimantId)
        if (claim.status != DeathClaimStatus.IN_PAYMENT || claimant.paymentRef != null) return
        remit(
            claim.contractId,
            "BENEFICIARY_WITHHOLDING",
            requireNotNull(claimant.tax),
            key("death", claimId, "$claimantId-tax"),
        )
        val contract = contract(claim.contractId)
        val ref = pay(
            key("death", claimId, "$claimantId-pay"),
            contract,
            "DEATH_BENEFIT",
            claimant.name,
            requireNotNull(claimant.iban),
            requireNotNull(claimant.net),
            currency(contract),
        )
        stores.claims.save(claim.markClaimantPaid(claimantId, ref, now()))
    }

    suspend fun deathSettle(claimId: UUID) {
        val claim = requireNotNull(stores.claims.findById(claimId))
        if (claim.status != DeathClaimStatus.IN_PAYMENT) return
        val contract = contract(claim.contractId)
        if (contract.status == ContractStatus.TERMINATING) stores.contracts.save(contract.close(now()))
        stores.claims.save(claim.settle(now()))
    }

    // ---- helpers ----

    private suspend fun remit(contractId: UUID, kind: String, amount: BigDecimal, key: String) {
        if (amount.signum() > 0) gw.tax.remit(contractId, kind, amount, key)
    }

    @Suppress("LongParameterList")
    private suspend fun pay(
        key: String,
        contract: PensionContract,
        purpose: String,
        creditor: String,
        iban: String,
        amount: BigDecimal,
        currency: String,
    ): String {
        if (amount.signum() == 0) return NO_PAYMENT
        val stored = stores.instructions.recordIfAbsent(
            PaymentInstruction(key, contract.id, purpose, amount, currency, iban, InstructionStatus.PENDING),
        )
        stored.paymentRef?.let { return it }
        check(stored.amount.compareTo(amount) == 0) { "instruction $key exists with a different amount" }
        val ref = gw.payments.pay(
            PaymentOrder(key, contract.id, creditor, iban, amount, currency, "PENSION $purpose ${contract.id}"),
        )
        stores.instructions.markSent(key, ref)
        // #12379: only a payment to the participant is announced to them — never a death benefit
        // to a claimant. After markSent, so a replayed activity (paymentRef already set) is silent.
        if (creditor == contract.participantPartyId.toString()) {
            ParticipantNotices.send(
                gw.notifier,
                ParticipantNotices.payoutExecuted(
                    contract.participantPartyId,
                    contract.id,
                    purpose,
                    amount,
                    currency,
                    iban,
                ),
            )
        }
        return ref
    }

    private fun currency(contract: PensionContract) = contract.schedule.currency

    private suspend fun contract(id: UUID): PensionContract =
        requireNotNull(stores.contracts.findById(id)) { "contract $id vanished" }

    /** Sells units worth [amount] through the shared fund port; the proceeds are the amount ordered. */
    private suspend fun redeem(contractId: UUID, amount: BigDecimal, idempotencyKey: String): BigDecimal =
        gw.fund.redeem(contractId, amount, currency(contract(contractId)), idempotencyKey).amount

    private suspend fun payout(id: UUID): PayoutRequest = requireNotNull(stores.payouts.findById(id)) {
        "payout $id vanished"
    }

    companion object {
        const val NO_PAYMENT = "NO-PAYMENT"

        fun key(kind: String, id: UUID, step: String) = "pension-$kind-$id-$step"
    }
}
