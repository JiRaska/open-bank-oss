// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.exit

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.domain.model.PayoutForm
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class InstallmentStatus { PENDING, PAID }

data class Installment(
    val seq: Int,
    val dueDate: LocalDate,
    val gross: BigDecimal,
    val tax: BigDecimal,
    val net: BigDecimal,
    val status: InstallmentStatus = InstallmentStatus.PENDING,
    val paymentRef: String? = null,
) {
    init {
        require(seq >= 1) { "installment seq starts at 1" }
        require(net.compareTo(gross - tax) == 0) { "installment net must equal gross - tax" }
    }
}

/**
 * The decumulation plan of a phased withdrawal or fixed-period pension: monthly installments whose
 * gross and tax are each split with [ExitMoney.splitEvenly], so the schedule sums EXACTLY to the
 * binding quote — the installments are the quote, cut into months.
 */
data class PayoutSchedule(val installments: List<Installment>) {
    init {
        require(installments.isNotEmpty()) { "a schedule has at least one installment" }
        require(installments.map { it.seq } == (1..installments.size).toList()) { "installments are numbered 1..n" }
    }

    val totalGross: BigDecimal get() = ExitMoney.sum(installments.map { it.gross })
    val totalTax: BigDecimal get() = ExitMoney.sum(installments.map { it.tax })
    val totalNet: BigDecimal get() = ExitMoney.sum(installments.map { it.net })
    val allPaid: Boolean get() = installments.all { it.status == InstallmentStatus.PAID }

    fun overdue(today: LocalDate): List<Installment> =
        installments.filter { it.status == InstallmentStatus.PENDING && it.dueDate.isBefore(today) }

    /** Idempotent: paying an already-paid installment again keeps the first reference. */
    fun markPaid(seq: Int, ref: String): PayoutSchedule = copy(
        installments = installments.map {
            if (it.seq == seq && it.status == InstallmentStatus.PENDING) {
                it.copy(status = InstallmentStatus.PAID, paymentRef = ref)
            } else {
                it
            }
        },
    )

    companion object {
        fun monthly(firstDue: LocalDate, months: Int, quote: PayoutQuote): PayoutSchedule {
            require(months >= 1) { "a schedule needs at least one month" }
            val gross = ExitMoney.splitEvenly(quote.grossAmount, months)
            val tax = if (quote.taxWithheld.signum() == 0) {
                List(months) { BigDecimal.ZERO.setScale(ExitMoney.SCALE) }
            } else {
                ExitMoney.splitEvenly(quote.taxWithheld, months)
            }
            return PayoutSchedule(
                (0 until months).map { i ->
                    Installment(i + 1, firstDue.plusMonths(i.toLong()), gross[i], tax[i], gross[i] - tax[i])
                },
            )
        }
    }
}

enum class PayoutStatus {
    QUOTED,
    CONFIRMED,
    IN_PAYMENT,
    COMPLETED,
    EXPIRED,

    /** ANNUITY only: the policy failed and the pack returned the premium to the contract (#12383). */
    REVERSED,
    ;

    fun canMoveTo(target: PayoutStatus): Boolean = target in EDGES.getValue(this)

    private companion object {
        val EDGES: Map<PayoutStatus, Set<PayoutStatus>> = mapOf(
            QUOTED to setOf(CONFIRMED, EXPIRED),
            CONFIRMED to setOf(IN_PAYMENT),
            IN_PAYMENT to setOf(COMPLETED, REVERSED),
            COMPLETED to emptySet(),
            EXPIRED to emptySet(),
            REVERSED to emptySet(),
        )
    }
}

/** An annuity bought from an insurer with the net payout (ANNUITY form). */
data class AnnuityPolicy(val policyRef: String, val insurerRef: String, val monthlyAmount: BigDecimal)

/**
 * A regular payout or a partial (early) withdrawal (ADR-0334 §4 step 7). Like the termination
 * notice, QUOTED is binding: the participant confirms the exact quote under SCA, and execution
 * pays [quote] — or [schedule], which is [quote] split into months — never a recomputation.
 *
 * `EARLY_WITHDRAWAL` is the partial form: it leaves the contract ACTIVE. Every other form ends it.
 */
@Suppress("TooManyFunctions") // one function per payout lifecycle edge and destination rule
data class PayoutRequest(
    val id: UUID,
    val contractId: UUID,
    val participantPartyId: UUID,
    val status: PayoutStatus,
    val quote: PayoutQuote,
    val quotedAt: Instant,
    val quoteExpiresAt: Instant,
    val schedule: PayoutSchedule? = null,
    val payoutIban: String? = null,
    /** A participant-requested new account, effective for installments due on/after [pendingPayoutIbanFrom]. */
    val pendingPayoutIban: String? = null,
    val pendingPayoutIbanFrom: LocalDate? = null,
    /** False until the participant has been notified of the pending change; until then it never applies. */
    val pendingPayoutIbanNotified: Boolean = false,
    val scaChallengeId: String? = null,
    val confirmedAt: Instant? = null,
    val idempotencyKey: String? = null,
    val redeemedAmount: BigDecimal? = null,
    val paymentRef: String? = null,
    val annuity: AnnuityPolicy? = null,
    val updatedAt: Instant,
    /** Optimistic-lock version of the stored row (set on load, checked on save; ADR-0334 S8). */
    val version: Int = 0,
) {
    init {
        if (schedule != null) {
            require(schedule.totalGross.compareTo(quote.grossAmount) == 0) { "schedule must sum to the quoted gross" }
            require(schedule.totalNet.compareTo(quote.netAmount) == 0) { "schedule must sum to the quoted net" }
        }
    }

    val form: PayoutForm get() = quote.form
    val partial: Boolean get() = quote.form == PayoutForm.EARLY_WITHDRAWAL
    val scheduled: Boolean get() = quote.form in SCHEDULED_FORMS

    val quoteHash: String get() = sha256("$id|$contractId|${quote.canonical()}|$quoteExpiresAt")

    /**
     * What the confirmation's SCA challenge signs (ADR-0334 S8): the quote AND the destination
     * account. Execution pays only the account stored with this signature.
     */
    fun signingHash(iban: String): String = sha256("$quoteHash|payout-account|$iban")

    /** What an SCA challenge must be bound to for moving the remaining payments to [iban]. */
    fun accountChangeHash(iban: String): String = sha256("$id|$contractId|payout-account-change|$iban")

    /**
     * The participant moves the REMAINING installments of a running scheduled payout to another
     * verified own account (ADR-0334 S8). The fraud controls are INVARIANTS of this aggregate, not
     * of a caller:
     * - nothing already signed is redirected: a single-payment form has no later payment, an
     *   unconfirmed quote has no account yet (its account is signed at confirmation);
     * - the hold is fixed here ([ACCOUNT_CHANGE_HOLD_DAYS]); no caller can shorten it;
     * - the new account is used only once the participant has been NOTIFIED
     *   ([markAccountChangeNotified]) AND the hold has elapsed at PAYMENT time AND the installment
     *   falls due after the hold — a late, early or swept installment cannot pick it up sooner;
     * - one change at a time: a second one is refused until the first has taken effect.
     */
    fun changePayoutAccount(iban: String, scaChallengeId: String, today: LocalDate, now: Instant): PayoutRequest {
        val effectiveFrom = today.plusDays(ACCOUNT_CHANGE_HOLD_DAYS)
        check(scheduled) { "only a scheduled payout has later payments to redirect" }
        check(status == PayoutStatus.CONFIRMED || status == PayoutStatus.IN_PAYMENT) {
            "the payout is $status; there is no running payout to redirect"
        }
        val remaining = schedule?.installments.orEmpty()
            .filter { it.status == InstallmentStatus.PENDING && !it.dueDate.isBefore(effectiveFrom) }
        check(remaining.isNotEmpty()) { "no installment falls due after the hold period; nothing to redirect" }
        check(pendingPayoutIban == null || pendingInEffect(today)) {
            "an account change is already pending until $pendingPayoutIbanFrom"
        }
        // A change that has taken effect becomes the account of record before the next one is held.
        val current = if (pendingInEffect(today)) pendingPayoutIban else payoutIban
        check(iban != current) { "the payout already goes to this account" }
        return copy(
            payoutIban = current,
            pendingPayoutIban = iban,
            pendingPayoutIbanFrom = effectiveFrom,
            pendingPayoutIbanNotified = false,
            scaChallengeId = scaChallengeId,
            updatedAt = now,
        )
    }

    /** The participant was told about the pending change on their known channel; only now can it apply. */
    fun markAccountChangeNotified(now: Instant): PayoutRequest {
        checkNotNull(pendingPayoutIban) { "no account change is pending" }
        return copy(pendingPayoutIbanNotified = true, updatedAt = now)
    }

    private fun pendingInEffect(today: LocalDate): Boolean {
        val from = pendingPayoutIbanFrom ?: return false
        return pendingPayoutIban != null && pendingPayoutIbanNotified && !today.isBefore(from)
    }

    /**
     * The account an installment due on [dueDate] and paid on [paymentDay] goes to: the signed
     * account, unless a notified change's hold has elapsed by [paymentDay] and the installment
     * falls due on or after it.
     */
    fun accountFor(dueDate: LocalDate, paymentDay: LocalDate): String? {
        val from = pendingPayoutIbanFrom
        return if (from != null &&
            pendingInEffect(paymentDay) &&
            !dueDate.isBefore(from)
        ) {
            pendingPayoutIban
        } else {
            payoutIban
        }
    }

    fun confirm(
        iban: String,
        scaChallengeId: String,
        idempotencyKey: String,
        today: LocalDate,
        now: Instant,
    ): PayoutRequest {
        check(now.isBefore(quoteExpiresAt)) { "the payout quote expired at $quoteExpiresAt; request a new one" }
        val plan = if (scheduled) {
            PayoutSchedule.monthly(today.plusMonths(1).withDayOfMonth(1), requireNotNull(quote.months), quote)
        } else {
            null
        }
        return moveTo(PayoutStatus.CONFIRMED, now).copy(
            payoutIban = iban,
            scaChallengeId = scaChallengeId,
            confirmedAt = now,
            idempotencyKey = idempotencyKey,
            schedule = plan,
        )
    }

    /** Idempotent: a retried redemption keeps the first proceeds. */
    fun markRedeemed(proceeds: BigDecimal, now: Instant): PayoutRequest = if (status ==
        PayoutStatus.IN_PAYMENT
    ) {
        this
    } else {
        moveTo(PayoutStatus.IN_PAYMENT, now).copy(redeemedAmount = ExitMoney.round(proceeds))
    }

    /** Scheduled forms redeem per installment; entering IN_PAYMENT is idempotent. */
    fun startInstallments(now: Instant): PayoutRequest {
        check(scheduled) { "only a scheduled payout pays in installments" }
        return if (status == PayoutStatus.IN_PAYMENT) this else moveTo(PayoutStatus.IN_PAYMENT, now)
    }

    /** ANNUITY whose premium came back to the contract (pack rule, #12383): nothing was paid out. */
    fun reverseAnnuity(now: Instant): PayoutRequest {
        check(form == PayoutForm.ANNUITY && annuity == null && paymentRef == null) {
            "only an unsettled annuity payout can be reversed"
        }
        return if (status == PayoutStatus.REVERSED) this else moveTo(PayoutStatus.REVERSED, now)
    }

    fun markPaid(ref: String, now: Instant): PayoutRequest {
        check(status == PayoutStatus.IN_PAYMENT && !scheduled) { "a single payment needs IN_PAYMENT, was $status" }
        return copy(paymentRef = paymentRef ?: ref, updatedAt = now)
    }

    fun markAnnuityPurchased(policy: AnnuityPolicy, now: Instant): PayoutRequest {
        check(status == PayoutStatus.IN_PAYMENT && form == PayoutForm.ANNUITY) {
            "no annuity purchase in $status/$form"
        }
        return copy(annuity = annuity ?: policy, updatedAt = now)
    }

    fun markInstallmentPaid(seq: Int, ref: String, now: Instant): PayoutRequest {
        check(status == PayoutStatus.IN_PAYMENT) { "installments are paid IN_PAYMENT, was $status" }
        val plan = checkNotNull(schedule) { "payout $id has no schedule" }
        require(plan.installments.any { it.seq == seq }) { "installment $seq does not exist" }
        return copy(schedule = plan.markPaid(seq, ref), updatedAt = now)
    }

    fun complete(now: Instant): PayoutRequest {
        val settled = when {
            scheduled -> schedule?.allPaid == true
            // A refused/failed annuity whose premium went to the client is settled by that payment.
            form == PayoutForm.ANNUITY -> annuity != null || paymentRef != null
            else -> paymentRef != null
        }
        check(settled) { "payout $id is not fully settled" }
        return moveTo(PayoutStatus.COMPLETED, now)
    }

    fun expire(now: Instant) = moveTo(PayoutStatus.EXPIRED, now)

    private fun moveTo(target: PayoutStatus, now: Instant): PayoutRequest {
        check(status.canMoveTo(target)) { "payout $status -> $target is not allowed" }
        return copy(status = target, updatedAt = now)
    }

    companion object {
        val SCHEDULED_FORMS = setOf(PayoutForm.PHASED_WITHDRAWAL, PayoutForm.FIXED_PERIOD_PENSION)

        /**
         * Hold between a payout-account change and the first payment to the new account (fraud
         * control, ADR-0334 S8). Owned by the aggregate so no caller can shorten it.
         */
        const val ACCOUNT_CHANGE_HOLD_DAYS = 3L

        fun quote(contractId: UUID, participantPartyId: UUID, quote: PayoutQuote, validityDays: Int, now: Instant) =
            PayoutRequest(
                id = Ids.newId(),
                contractId = contractId,
                participantPartyId = participantPartyId,
                status = PayoutStatus.QUOTED,
                quote = quote,
                quotedAt = now,
                quoteExpiresAt = now.plus(Duration.ofDays(validityDays.toLong())),
                updatedAt = now,
            )
    }
}
