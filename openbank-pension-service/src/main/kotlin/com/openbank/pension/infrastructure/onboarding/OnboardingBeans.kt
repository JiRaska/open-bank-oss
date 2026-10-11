// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding

import com.openbank.pension.application.ProviderBoundary
import com.openbank.pension.application.onboarding.KeyInformationDocumentPort
import com.openbank.pension.application.onboarding.OnboardingApplicationRepository
import com.openbank.pension.application.onboarding.OnboardingRulesRegistry
import com.openbank.pension.application.onboarding.OnboardingService
import com.openbank.pension.application.onboarding.PartyKycPort
import com.openbank.pension.application.onboarding.PartyRelationPort
import com.openbank.pension.application.onboarding.PensionOrchestrator
import com.openbank.pension.application.onboarding.SignatureVerificationPort
import com.openbank.pension.application.onboarding.SuitabilityAssessmentRepository
import com.openbank.pension.application.onboarding.TransactionRunner
import com.openbank.pension.application.onboarding.TransferCounterpartyPort
import com.openbank.pension.application.onboarding.TransferRequestRepository
import com.openbank.pension.application.onboarding.TransferService
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.OnboardingActivationPort
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.ContributionService
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.questionnaire.QuestionSetRegistry
import com.openbank.pension.domain.questionnaire.StrategyInstrumentMappingPort
import com.openbank.pension.infrastructure.onboarding.pack.OnboardingRulesLoader
import com.openbank.pension.infrastructure.onboarding.pack.StaticOnboardingRulesRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock
import java.util.UUID

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
        relations: PartyRelationPort,
        documents: KeyInformationDocumentPort,
        signatures: SignatureVerificationPort,
        orchestrator: PensionOrchestrator,
        tx: TransactionRunner,
        clock: Clock,
        questionSets: QuestionSetRegistry,
        providerBoundary: ProviderBoundary,
        instrumentMappings: StrategyInstrumentMappingPort,
    ): OnboardingService = OnboardingService(
        applications, assessments, transfers, contracts, packs, rules, kyc, relations, documents, signatures,
        orchestrator, tx, clock, questionSets, providerBoundary, instrumentMappings,
    )

    /** The port slice S3 calls on a first contribution; backed by the real onboarding workflow. */
    @Produces
    @ApplicationScoped
    fun onboardingActivationPort(onboarding: OnboardingService): OnboardingActivationPort =
        object : OnboardingActivationPort {
            override suspend fun awaitsFirstContribution(contractId: UUID) =
                onboarding.awaitsFirstContribution(contractId)

            override suspend fun firstContributionReceived(contractId: UUID) =
                onboarding.firstContributionReceived(contractId)
        }

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
        contributions: ContributionService,
        tx: TransactionRunner,
        clock: Clock,
        notifier: ParticipantNotifier,
    ): TransferService = TransferService(
        transfers, applications, contracts, packs, counterparties, funds, signatures, orchestrator, onboarding,
        { contractId, transferId, amount, currency, valueDate ->
            contributions.bookTransferIn(contractId, transferId, amount, currency, valueDate)
        },
        tx, clock, notifier,
    )
}
