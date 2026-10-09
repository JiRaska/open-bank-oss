// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.domain.exit.ExitCalculator
import com.openbank.pension.domain.exit.TerminationNotice
import com.openbank.pension.domain.exit.TerminationStatus
import com.openbank.pension.domain.exit.exitRules
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.PensionContract
import java.time.LocalDate
import java.util.UUID

data class SignTerminationCommand(
    val caller: Caller,
    val contractId: UUID,
    val noticeId: UUID,
    val scaChallengeId: String,
    val iban: String,
    val idempotencyKey: String,
)

/**
 * Early termination on client notice (ADR-0334 §4 step 6). [quote] is the binding preview; [sign]
 * accepts exactly that quote under SCA and hands the notice to the durable workflow, which waits
 * out the pack's notice period and pays what was quoted.
 *
 * Ownership is S1's: every read goes through [PensionContractUseCase.get] with the caller, so a
 * contract of another participant is NOT FOUND here exactly as it is there.
 */
class TerminationService(
    private val contractsUseCase: PensionContractUseCase,
    private val ctx: ExitContext,
    private val launcher: ExitWorkflowLauncher,
) {
    private val stores get() = ctx.stores

    suspend fun quote(caller: Caller, contractId: UUID): TerminationNotice {
        caller.requireParticipant()
        val contract = contractsUseCase.get(caller, contractId)
        check(contract.status == ContractStatus.ACTIVE || contract.status == ContractStatus.SUSPENDED) {
            "early termination needs an ACTIVE or SUSPENDED contract, was ${contract.status}"
        }
        check(stores.claims.findByContract(contractId) == null) { "a death claim is registered for this contract" }
        val pack = ctx.packs.pinnedFor(contract)
        val rules = pack.exitRules()
        val today = LocalDate.now(ctx.clock)
        val eligibility = ExitCalculator.eligibility(contract, pack, today)
        check(!eligibility.conditionsMet) {
            "payout conditions are met; request a regular payout instead of an early termination"
        }
        val quote = ExitCalculator.terminationQuote(
            pack,
            ctx.gateways.fund.valuation(contractId, contract.schedule.currency).amount,
            ctx.gateways.incentives.balance(contractId, today),
            today.year,
        )
        return stores.notices.save(
            TerminationNotice.quote(
                contractId,
                contract.participantPartyId,
                quote,
                rules.termination.quoteValidityDays,
                ctx.clock.instant(),
            ),
        )
    }

    suspend fun sign(command: SignTerminationCommand): TerminationNotice {
        command.caller.requireParticipant()
        val contract = contractsUseCase.get(command.caller, command.contractId)
        val notice = load(command.contractId, command.noticeId)
        val noticeDays = ctx.packs.pinnedFor(contract).exitRules().termination.noticePeriodDays
        if (notice.status != TerminationStatus.QUOTED) {
            check(notice.idempotencyKey == command.idempotencyKey) {
                "termination notice is ${notice.status}; it cannot be signed again"
            }
            // A replay of the signature that succeeded: re-assert the side effects, all idempotent.
            ensureTerminating(contract)
            if (notice.status == TerminationStatus.SIGNED) launcher.startTermination(notice.id, noticeDays)
            return notice
        }
        val now = ctx.clock.instant()
        if (!now.isBefore(notice.quoteExpiresAt)) {
            stores.notices.save(notice.expire(now))
            error("the termination quote expired; request a new one")
        }
        val iban = IbanRule.normalise(command.iban)
        ctx.gateways.verifySignatureAndAccount(
            contract.participantPartyId,
            command.scaChallengeId,
            // The signature covers the quote AND the account (S8): what is approved is where it goes.
            notice.signingHash(iban),
            iban,
        )
        val signed = stores.notices.save(
            notice.sign(
                iban,
                command.scaChallengeId,
                command.idempotencyKey,
                noticeDays,
                now,
                LocalDate.now(ctx.clock),
            ),
        )
        ensureTerminating(contract)
        launcher.startTermination(signed.id, noticeDays)
        return signed
    }

    suspend fun get(caller: Caller, contractId: UUID, noticeId: UUID): TerminationNotice {
        contractsUseCase.get(caller, contractId)
        return load(contractId, noticeId)
    }

    private suspend fun ensureTerminating(contract: PensionContract) {
        if (contract.status != ContractStatus.TERMINATING) {
            stores.contracts.save(contract.requestTermination(ctx.clock.instant()))
        }
    }

    private suspend fun load(contractId: UUID, noticeId: UUID): TerminationNotice =
        stores.notices.findById(noticeId)?.takeIf { it.contractId == contractId }
            ?: throw ExitNotFoundException("termination notice $noticeId not found")
}
