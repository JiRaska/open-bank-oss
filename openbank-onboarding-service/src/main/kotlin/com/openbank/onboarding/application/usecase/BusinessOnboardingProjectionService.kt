// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.openbank.onboarding.application.port.out.BusinessOnboardingRepository
import com.openbank.onboarding.domain.model.BusinessFunnelStage
import com.openbank.onboarding.domain.model.BusinessOnboardingEvent
import com.openbank.onboarding.domain.model.BusinessOnboardingRecord
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Projects `openbank.kyb.events` into the business funnel read model (ADR-0284 D6). */
@ApplicationScoped
class BusinessOnboardingProjectionService {

    @Inject lateinit var repo: BusinessOnboardingRepository

    @Inject lateinit var clock: Clock

    suspend fun project(event: BusinessOnboardingEvent) {
        val existing = repo.findByCaseId(event.caseId)
        val record = BusinessOnboardingRecord(
            caseId = event.caseId,
            identifierScheme = event.identifierScheme,
            identifier = event.identifier,
            country = event.country,
            // The register facts arrive with the verification event and are absent from the events
            // before it. Keeping what we already have beats overwriting a name with null on the
            // next signer event — the board would blank out a company mid-flow.
            legalName = event.legalName ?: existing?.legalName,
            legalFormClass = event.legalFormClass ?: existing?.legalFormClass,
            initiatorPartyId = event.initiatorPartyId,
            entityPartyId = event.entityPartyId ?: existing?.entityPartyId,
            caseStatus = event.status,
            stage = BusinessFunnelStage.of(event.status),
            requiredSignatures = event.requiredSignatures ?: existing?.requiredSignatures,
            signedCount = event.signedCount,
            // The reason is cleared when the case leaves review: a stale "power of attorney
            // required" under a live customer is a sentence an operator would act on.
            reviewReason = if (event.status.name == "MANUAL_REVIEW") event.reviewReason else null,
            createdAt = existing?.createdAt ?: event.occurredAt,
            updatedAt = Instant.now(clock),
        )
        repo.upsert(record, event.occurredAt)
    }

    /** GDPR Art. 17 for a human who took part in a case; the entity's own facts survive. */
    suspend fun eraseParty(partyId: UUID) = repo.anonymizeParty(partyId)
}
