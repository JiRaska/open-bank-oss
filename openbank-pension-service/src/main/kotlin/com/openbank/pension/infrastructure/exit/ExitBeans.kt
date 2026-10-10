// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit

import com.openbank.pension.application.exit.AnnuityPlacementPort
import com.openbank.pension.application.exit.BeneficiaryVerificationPort
import com.openbank.pension.application.exit.DeathClaimRepository
import com.openbank.pension.application.exit.DeathClaimService
import com.openbank.pension.application.exit.ExitContext
import com.openbank.pension.application.exit.ExitExecutionService
import com.openbank.pension.application.exit.ExitGateways
import com.openbank.pension.application.exit.ExitStores
import com.openbank.pension.application.exit.ExitWorkflowLauncher
import com.openbank.pension.application.exit.IncentiveClawbackPort
import com.openbank.pension.application.exit.OwnAccountVerificationPort
import com.openbank.pension.application.exit.ParticipantNotificationPort
import com.openbank.pension.application.exit.PaymentInstructionRepository
import com.openbank.pension.application.exit.PayoutPaymentPort
import com.openbank.pension.application.exit.PayoutRequestRepository
import com.openbank.pension.application.exit.PayoutService
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.exit.TaxWithholdingPort
import com.openbank.pension.application.exit.TerminationNoticeRepository
import com.openbank.pension.application.exit.TerminationService
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

/** CDI wiring of the framework-free exit services (slice S5). */
@ApplicationScoped
class ExitBeans {

    @Produces
    @Singleton
    fun exitStores(
        contracts: PensionContractRepository,
        notices: TerminationNoticeRepository,
        payouts: PayoutRequestRepository,
        claims: DeathClaimRepository,
        instructions: PaymentInstructionRepository,
    ) = ExitStores(contracts, notices, payouts, claims, instructions)

    @Produces
    @Singleton
    @Suppress("LongParameterList")
    fun exitGateways(
        fund: FundAdministrationPort,
        incentives: IncentiveClawbackPort,
        tax: TaxWithholdingPort,
        payments: PayoutPaymentPort,
        annuities: AnnuityPlacementPort,
        accounts: OwnAccountVerificationPort,
        sca: ScaVerificationPort,
        beneficiaryKyc: BeneficiaryVerificationPort,
        notifications: ParticipantNotificationPort,
        notifier: ParticipantNotifier,
    ) = ExitGateways(fund, incentives, tax, payments, annuities, accounts, sca, beneficiaryKyc, notifications, notifier)

    @Produces
    @Singleton
    fun exitContext(stores: ExitStores, gateways: ExitGateways, packs: JurisdictionPackRegistry, clock: Clock) =
        ExitContext(stores, gateways, packs, clock)

    @Produces
    @ApplicationScoped
    fun terminationService(contracts: PensionContractUseCase, ctx: ExitContext, launcher: ExitWorkflowLauncher) =
        TerminationService(contracts, ctx, launcher)

    @Produces
    @ApplicationScoped
    fun payoutService(contracts: PensionContractUseCase, ctx: ExitContext, launcher: ExitWorkflowLauncher) =
        PayoutService(contracts, ctx, launcher)

    @Produces
    @ApplicationScoped
    fun deathClaimService(contracts: PensionContractUseCase, ctx: ExitContext, launcher: ExitWorkflowLauncher) =
        DeathClaimService(contracts, ctx, launcher)

    @Produces
    @ApplicationScoped
    fun exitExecutionService(ctx: ExitContext) = ExitExecutionService(ctx)
}
