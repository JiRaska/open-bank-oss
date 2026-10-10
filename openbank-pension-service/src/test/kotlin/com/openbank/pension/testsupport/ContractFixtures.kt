// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.testsupport

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * TEST FIXTURE: an ACTIVE contract without walking the onboarding journey. The product has no such
 * path — since ADR-0334 S8 a contract activates only through the onboarding workflow (KID, SCA,
 * cooling-off) or a completed transfer-in. Tests whose subject is something else (an exit, a
 * route's ownership rule) start from this; the onboarding path itself is journeyed end to end in
 * `PensionFullLifecycleJourneyE2E` and `OnboardingApiIT`.
 */
object ContractFixtures {
    suspend fun activeContract(
        contracts: PensionContractUseCase,
        repository: PensionContractRepository,
        party: UUID,
        beneficiaries: List<Beneficiary> = listOf(
            Beneficiary("Jane Doe", null, BigDecimal("60")),
            Beneficiary("John Doe", null, BigDecimal("40")),
        ),
        productLine: ProductLine = ProductLine.DPS,
        birthDate: LocalDate = LocalDate.parse("1985-05-05"),
        startDate: LocalDate = LocalDate.now(),
    ): UUID {
        val draft = contracts.createDraft(
            CreateDraftCommand(
                participantPartyId = party, productLine = productLine, jurisdiction = "CZ",
                providerEntityId = UUID.randomUUID(),
                providerType = if (productLine == ProductLine.DIP) ProviderType.BANK else ProviderType.PENSION_COMPANY,
                birthDate = birthDate, residencyCountry = "CZ", residencyEvidence = emptySet(), hasGuardian = false,
                schedule = ContributionSchedule(BigDecimal("1700"), "CZK", ContributionFrequency.MONTHLY),
                initialStrategy = "CONSERVATIVE",
                beneficiaries = beneficiaries,
                idempotencyKey = UUID.randomUUID().toString(),
            ),
        )
        val pending = contracts.submit(Caller.customer(party), draft.id)
        repository.save(pending.activate(startDate, Instant.now()))
        return draft.id
    }
}
