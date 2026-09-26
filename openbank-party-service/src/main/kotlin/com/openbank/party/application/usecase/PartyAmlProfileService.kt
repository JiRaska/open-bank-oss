// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.DeclareAmlProfileCommand
import com.openbank.party.application.port.`in`.PartyAmlProfileUseCase
import com.openbank.party.application.port.out.PartyAmlProfileRepository
import com.openbank.party.application.port.out.PartyChangeMetricsPort
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.domain.model.AmlProfileNotApplicableException
import com.openbank.party.domain.model.AmlProfiles
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyActor
import com.openbank.party.domain.model.PartyAmlProfile
import com.openbank.party.domain.model.PartyChange
import com.openbank.party.domain.model.PartyEvents
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The personal AML profile, filled once and reused by every later product (and by business
 * onboarding, which reads the derived `parties` facts). A separate use case from [PartyService],
 * which is already large; it shares the party repository and the outbox discipline (#4007).
 */
@ApplicationScoped
class PartyAmlProfileService : PartyAmlProfileUseCase {

    @Inject lateinit var partyRepo: PartyRepository

    @Inject lateinit var profileRepo: PartyAmlProfileRepository

    @Inject lateinit var changeMetrics: PartyChangeMetricsPort

    @Inject lateinit var clock: Clock

    /**
     * ISO alpha-2 countries whose tax residency routes the declaration to enhanced due diligence.
     * The default is a snapshot of the EU high-risk third-country list (Delegated Regulation (EU)
     * 2016/1675 as amended) plus the FATF call-for-action jurisdictions; operators must keep it
     * current through `OPENBANK_PARTY_AML_HIGH_RISK_COUNTRIES` — a stale list under-routes.
     */
    @ConfigProperty(name = "openbank.party.aml.high-risk-countries", defaultValue = DEFAULT_HIGH_RISK_COUNTRIES)
    lateinit var highRiskCountries: List<String>

    override suspend fun getAmlProfile(partyId: UUID): PartyAmlProfile? {
        partyRepo.findById(partyId) ?: throw PartyNotFoundException(partyId)
        return profileRepo.findCurrent(partyId)
    }

    override suspend fun declareAmlProfile(cmd: DeclareAmlProfileCommand): PartyAmlProfile {
        val party = partyRepo.findById(cmd.partyId) ?: throw PartyNotFoundException(cmd.partyId)
        requireEligible(party)
        cmd.declaration.validate()
        val now = Instant.now(clock)
        val previous = profileRepo.findCurrent(party.id)
        val profile = PartyAmlProfile(
            partyId = party.id,
            version = (previous?.version ?: 0) + 1,
            declaration = cmd.declaration,
            riskFactors = AmlProfiles.riskFactors(
                cmd.declaration,
                highRiskCountries.map { it.trim().uppercase() }.toSet(),
            ),
            declaredAt = now,
            declaredBy = cmd.declaredBy,
        )
        val facts = profile.derivedFacts()
        val updated = party.copy(
            pepFlag = facts.pepFlag,
            pepCategory = facts.pepCategory?.name,
            fatcaStatus = facts.fatcaStatus.name,
            crsStatus = facts.crsStatus.name,
            updatedAt = now,
        )
        changeMetrics.changeClassified(PartyChange.classify(party, updated).materiality)
        val event = PartyEvents.amlProfileDeclared(party, updated, profile, now, PartyActor.system("party-aml-profile"))
        return profileRepo.saveNewVersion(profile, facts, event)
    }

    private fun requireEligible(party: Party) {
        val reason = when {
            party.partyType != PartyType.INDIVIDUAL ->
                "party ${party.id} is a ${party.partyType} — a personal AML profile belongs to a natural person"
            party.status == PartyStatus.CLOSED || party.status == PartyStatus.MERGED ->
                "party ${party.id} is ${party.status}"
            else -> null
        }
        if (reason != null) throw AmlProfileNotApplicableException(reason)
    }

    companion object {
        const val DEFAULT_HIGH_RISK_COUNTRIES =
            "AF,BF,BO,CD,CI,CM,DZ,HT,IR,KE,KP,LA,LB,MC,ML,MM,MZ,NA,NG,NP,SN,SS,SY,TT,TZ,VE,VG,VN,VU,YE,ZA,AO"
    }
}
