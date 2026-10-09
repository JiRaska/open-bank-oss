// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.domain.exit.ExitCalculator
import com.openbank.pension.domain.exit.ExitRules
import com.openbank.pension.domain.exit.PayoutEligibility
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.PayoutStatus
import com.openbank.pension.domain.exit.exitRules
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.pack.JurisdictionPack
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class PayoutQuoteCommand(
    val caller: Caller,
    val contractId: UUID,
    val form: PayoutForm,
    /** Required for a partial withdrawal; every other form pays out the whole value. */
    val amount: BigDecimal? = null,
    /** Required for a phased withdrawal or fixed-period pension. */
    val months: Int? = null,
)

data class ConfirmPayoutCommand(
    val caller: Caller,
    val contractId: UUID,
    val payoutId: UUID,
    val scaChallengeId: String,
    val iban: String,
    val idempotencyKey: String,
)

data class EligibilityView(val eligibility: PayoutEligibility, val allowedForms: Set<PayoutForm>, val partialAllowed: Boolean)

/**
 * Regular termination & payout and partial withdrawals (ADR-0334 §4 step 7). Same binding-quote
 * shape as early termination: [quote] fixes every amount, [confirm] accepts it under SCA and the
 * workflow executes it — a lump sum, an annuity purchase, or a monthly schedule.
 */
class PayoutService(
    private val contractsUseCase: PensionContractUseCase,
    private val ctx: ExitContext,
    private val launcher: ExitWorkflowLauncher,
) {
    private val stores get() = ctx.stores

    suspend fun eligibility(caller: Caller, contractId: UUID): EligibilityView {
        val contract = contractsUseCase.get(caller, contractId)
        val pack = ctx.packs.pinnedFor(contract)
        val result = ExitCalculator.eligibility(contract, pack, LocalDate.now(ctx.clock))
        val partial = pack.payout.earlyWithdrawalAllowed && pack.exit?.partialWithdrawal?.allowed == true
        return EligibilityView(result, pack.payout.allowedForms - PayoutForm.SURRENDER, partial)
    }

    suspend fun quote(command: PayoutQuoteCommand): PayoutRequest {
        command.caller.requireParticipant()
        val contract = contractsUseCase.get(command.caller, command.contractId)
        check(contract.status == ContractStatus.ACTIVE || contract.status == ContractStatus.SUSPENDED) {
            "a payout needs an ACTIVE or SUSPENDED contract, was ${contract.status}"
        }
        check(stores.claims.findByContract(contract.id) == null) { "a death claim is registered for this contract" }
        require(command.form != PayoutForm.SURRENDER) { "a surrender is an early termination; use the termination quote" }
        val pack = ctx.packs.pinnedFor(contract)
        val rules = pack.exitRules()
        val today = LocalDate.now(ctx.clock)
        val value = ctx.gateways.fund.redemptionValue(contract.id)
        val balance = ctx.gateways.incentives.balance(contract.id, today)
        val eligibility = ExitCalculator.eligibility(contract, pack, today)
        val (gross, months) = if (command.form == PayoutForm.EARLY_WITHDRAWAL) {
            partialAmount(contract, pack, rules, eligibility, value, command.amount, today) to null
        } else {
            check(eligibility.conditionsMet) { "payout conditions not met: ${eligibility.reasons.joinToString("; ")}" }
            require(command.amount == null) { "a ${command.form} pays out the whole value; omit amount" }
            value to months(rules, command.form, command.months)
        }
        val quote = ExitCalculator.payoutQuote(pack, command.form, value, gross, balance, months)
        return stores.payouts.save(
            PayoutRequest.quote(
                contract.id,
                contract.participantPartyId,
                quote,
                rules.termination.quoteValidityDays,
                ctx.clock.instant(),
            ),
        )
    }

    suspend fun confirm(command: ConfirmPayoutCommand): PayoutRequest {
        command.caller.requireParticipant()
        val contract = contractsUseCase.get(command.caller, command.contractId)
        val request = load(command.contractId, command.payoutId)
        if (request.status != PayoutStatus.QUOTED) {
            check(request.idempotencyKey == command.idempotencyKey) { "payout is ${request.status}; it cannot be confirmed again" }
            if (!request.partial) ensureTerminating(contract)
            if (request.status != PayoutStatus.COMPLETED) launcher.startPayout(request.id)
            return request
        }
        val now = ctx.clock.instant()
        if (!now.isBefore(request.quoteExpiresAt)) {
            stores.payouts.save(request.expire(now))
            throw IllegalStateException("the payout quote expired; request a new one")
        }
        check(contract.status == ContractStatus.ACTIVE || contract.status == ContractStatus.SUSPENDED) {
            "a payout needs an ACTIVE or SUSPENDED contract, was ${contract.status}"
        }
        val iban = IbanRule.normalise(command.iban)
        if (!ctx.gateways.sca.verify(contract.participantPartyId, command.scaChallengeId, request.quoteHash)) {
            throw ExitForbiddenException("strong customer authentication failed for this quote")
        }
        if (!ctx.gateways.accounts.isOwnVerifiedAccount(contract.participantPartyId, iban)) {
            throw ExitForbiddenException("the payout account is not a verified account of the participant")
        }
        val confirmed = stores.payouts.save(
            request.confirm(iban, command.scaChallengeId, command.idempotencyKey, LocalDate.now(ctx.clock), now),
        )
        if (!confirmed.partial) ensureTerminating(contract)
        launcher.startPayout(confirmed.id)
        return confirmed
    }

    suspend fun get(caller: Caller, contractId: UUID, payoutId: UUID): PayoutRequest {
        contractsUseCase.get(caller, contractId)
        return load(contractId, payoutId)
    }

    /** Scheduled payouts with an installment past due: the sweep re-starts their workflow. */
    suspend fun overdue(today: LocalDate): List<PayoutRequest> =
        stores.payouts.findInPayment().filter { it.schedule?.overdue(today)?.isNotEmpty() == true }

    @Suppress("LongParameterList")
    private suspend fun partialAmount(
        contract: PensionContract,
        pack: JurisdictionPack,
        rules: ExitRules,
        eligibility: PayoutEligibility,
        value: BigDecimal,
        amount: BigDecimal?,
        today: LocalDate,
    ): BigDecimal {
        val partial = rules.partialWithdrawal
        check(pack.payout.earlyWithdrawalAllowed && partial != null && partial.allowed) {
            "partial withdrawals are not allowed under this pack"
        }
        check(!partial.requiresPayoutConditions || eligibility.conditionsMet) {
            "payout conditions not met: ${eligibility.reasons.joinToString("; ")}"
        }
        val requested = requireNotNull(amount) { "amount is required for a partial withdrawal" }
        require(requested >= partial.minAmount) { "a partial withdrawal is at least ${partial.minAmount}" }
        require(requested <= value.multiply(partial.maxShareOfValue)) {
            "a partial withdrawal may take at most ${partial.maxShareOfValue} of the current value"
        }
        val thisYear = stores.payouts.findByContract(contract.id).count {
            it.partial && it.confirmedAt != null && it.confirmedAt.atZone(ZoneOffset.UTC).year == today.year
        }
        check(thisYear < partial.maxPerCalendarYear) {
            "at most ${partial.maxPerCalendarYear} partial withdrawal(s) per calendar year"
        }
        return requested
    }

    private fun months(rules: ExitRules, form: PayoutForm, months: Int?): Int? {
        val bounds = when (form) {
            PayoutForm.PHASED_WITHDRAWAL -> rules.phasedWithdrawal
            PayoutForm.FIXED_PERIOD_PENSION -> rules.fixedPeriodPension
            else -> return months.also { require(it == null) { "months applies only to a scheduled payout" } }
        } ?: throw IllegalStateException("the pack states no schedule bounds for $form")
        val m = requireNotNull(months) { "months is required for $form" }
        require(m in bounds.minMonths..bounds.maxMonths) { "months must be ${bounds.minMonths}..${bounds.maxMonths} for $form" }
        return m
    }

    private suspend fun ensureTerminating(contract: PensionContract) {
        if (contract.status != ContractStatus.TERMINATING) {
            stores.contracts.save(contract.requestTermination(ctx.clock.instant()))
        }
    }

    private suspend fun load(contractId: UUID, payoutId: UUID): PayoutRequest =
        stores.payouts.findById(payoutId)?.takeIf { it.contractId == contractId }
            ?: throw ExitNotFoundException("payout $payoutId not found")
}
