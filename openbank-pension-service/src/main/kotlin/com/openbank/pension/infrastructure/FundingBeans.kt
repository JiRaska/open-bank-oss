// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure

import com.openbank.pension.application.port.out.ClaimBatchRepository
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.ContributionRepository
import com.openbank.pension.application.port.out.EmployerDirectoryPort
import com.openbank.pension.application.port.out.EmployerEnrolmentRepository
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.IncentiveClaimRepository
import com.openbank.pension.application.port.out.IncentiveLedgerRepository
import com.openbank.pension.application.port.out.OnboardingActivationPort
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.application.port.out.PaymentMandatePort
import com.openbank.pension.application.port.out.StateIncentiveClaimPort
import com.openbank.pension.application.port.out.TaxCertificateDocumentPort
import com.openbank.pension.application.port.out.TaxYearSummaryRepository
import com.openbank.pension.application.port.out.UnmatchedPaymentRepository
import com.openbank.pension.application.usecase.ContributionService
import com.openbank.pension.application.usecase.IncentiveService
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** CDI wiring for the S3 funding use cases (ADR-0334 S3); kept apart from S1's [PensionBeans]. */
@ApplicationScoped
class FundingBeans {

    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList")
    fun contributionService(
        directory: ContractFundingDirectory,
        references: ContractReferenceRepository,
        contributions: ContributionRepository,
        unmatched: UnmatchedPaymentRepository,
        fund: FundAdministrationPort,
        employers: EmployerDirectoryPort,
        mandates: PaymentMandatePort,
        enrolments: EmployerEnrolmentRepository,
        activation: OnboardingActivationPort,
        clock: Clock,
    ): ContributionService = ContributionService(
        directory, references, contributions, unmatched, fund, employers, mandates, enrolments, activation, clock,
    )

    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList")
    fun incentiveService(
        directory: ContractFundingDirectory,
        references: ContractReferenceRepository,
        contributions: ContributionRepository,
        claims: IncentiveClaimRepository,
        batches: ClaimBatchRepository,
        ledger: IncentiveLedgerRepository,
        summaries: TaxYearSummaryRepository,
        registry: JurisdictionPackRegistry,
        channels: Instance<StateIncentiveClaimPort>,
        documents: TaxCertificateDocumentPort,
        contributionService: ContributionService,
        clock: Clock,
        notifier: ParticipantNotifier,
    ): IncentiveService = IncentiveService(
        directory, references, contributions, claims, batches, ledger, summaries, registry,
        channels.toList(), documents, contributionService, clock, notifier,
    )
}
