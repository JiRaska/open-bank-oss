// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding

import com.openbank.pension.application.onboarding.FundAdministrationPort
import com.openbank.pension.application.onboarding.KeyInformationDocumentPort
import com.openbank.pension.application.onboarding.OnboardingApplicationRepository
import com.openbank.pension.application.onboarding.OnboardingRulesRegistry
import com.openbank.pension.application.onboarding.OnboardingService
import com.openbank.pension.application.onboarding.PartyKycPort
import com.openbank.pension.application.onboarding.PensionOrchestrator
import com.openbank.pension.application.onboarding.SignatureVerificationPort
import com.openbank.pension.application.onboarding.SuitabilityAssessmentRepository
import com.openbank.pension.application.onboarding.TransactionRunner
import com.openbank.pension.application.onboarding.TransferCounterpartyPort
import com.openbank.pension.application.onboarding.TransferRequestRepository
import com.openbank.pension.application.onboarding.TransferService
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.infrastructure.onboarding.pack.OnboardingRulesLoader
import com.openbank.pension.infrastructure.onboarding.pack.StaticOnboardingRulesRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * CDI wiring for slice S2 (onboarding and transfers). Kept apart from S1's `PensionBeans` so the
 * slices do not edit one file. The rules registry is `@Startup`: a pack without onboarding rules
 * must stop the pod at boot, not answer 400 on the first application after a green deploy.
 */
@ApplicationScoped
class OnboardingBeans {

    @Produces
    @Startup
    @ApplicationScoped
    fun onboardingRules(packs: JurisdictionPackRegistry): OnboardingRulesRegistry =
        StaticOnboardingRulesRegistry(OnboardingRulesLoader.loadAll(), packs.all())

    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList")
    fun onboardingService(
        applications: OnboardingApplicationRepository,
        assessments: SuitabilityAssessmentRepository,
        transfers: TransferRequestRepository,
        contracts: PensionContractRepository,
        packs: JurisdictionPackRegistry,
        rules: OnboardingRulesRegistry,
        kyc: PartyKycPort,
        documents: KeyInformationDocumentPort,
        signatures: SignatureVerificationPort,
        orchestrator: PensionOrchestrator,
        tx: TransactionRunner,
        clock: Clock,
    ): OnboardingService = OnboardingService(
        applications, assessments, transfers, contracts, packs, rules, kyc, documents, signatures, orchestrator, tx, clock,
    )

    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList")
    fun transferService(
        transfers: TransferRequestRepository,
        applications: OnboardingApplicationRepository,
        contracts: PensionContractRepository,
        packs: JurisdictionPackRegistry,
        counterparties: TransferCounterpartyPort,
        funds: FundAdministrationPort,
        signatures: SignatureVerificationPort,
        orchestrator: PensionOrchestrator,
        onboarding: OnboardingService,
        tx: TransactionRunner,
        clock: Clock,
    ): TransferService = TransferService(
        transfers, applications, contracts, packs, counterparties, funds, signatures, orchestrator, onboarding, tx, clock,
    )
}
