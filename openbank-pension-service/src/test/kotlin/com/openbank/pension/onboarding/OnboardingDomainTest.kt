// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.onboarding

import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.ApplicantFacts
import com.openbank.pension.domain.onboarding.EsgPreference
import com.openbank.pension.domain.onboarding.IssuedKid
import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.OnboardingKind
import com.openbank.pension.domain.onboarding.OnboardingStatus
import com.openbank.pension.domain.onboarding.QuestionnaireAnswers
import com.openbank.pension.domain.onboarding.RecommendationReason
import com.openbank.pension.domain.onboarding.RiskLabel
import com.openbank.pension.domain.onboarding.StrategyRecommender
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.pack.TransferRules
import com.openbank.pension.domain.transfer.Counterparty
import com.openbank.pension.domain.transfer.FundsArrival
import com.openbank.pension.domain.transfer.TransferDirection
import com.openbank.pension.domain.transfer.TransferOrigin
import com.openbank.pension.domain.transfer.TransferRequest
import com.openbank.pension.domain.transfer.TransferStatus
import com.openbank.pension.domain.transfer.TransferTerms
import com.openbank.pension.infrastructure.onboarding.pack.OnboardingRulesLoader
import com.openbank.pension.infrastructure.onboarding.pack.StaticOnboardingRulesRegistry
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OnboardingDomainTest {

    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val today = LocalDate.parse("2026-10-09")
    private val packs = JurisdictionPackLoader.loadAll()
    private val registry = StaticOnboardingRulesRegistry(OnboardingRulesLoader.loadAll(), packs)
    private val dps = registry.rules("CZ", ProductLine.DPS, 1)
    private val dip = registry.rules("CZ", ProductLine.DIP, 1)
    private val party = UUID.randomUUID()

    private fun application(guardian: UUID? = null) = OnboardingApplication.start(
        partyId = party,
        kind = OnboardingKind.NEW_CONTRACT,
        productLine = ProductLine.DPS,
        jurisdiction = "CZ",
        packVersion = 1,
        providerEntityId = UUID.randomUUID(),
        providerType = ProviderType.PENSION_COMPANY,
        schedule = ContributionSchedule(BigDecimal("1000"), "CZK", ContributionFrequency.MONTHLY),
        applicant = ApplicantFacts(LocalDate.parse("1990-01-01"), "CZ", emptySet(), true, guardian),
        ceding = null,
        ineligibilityReasons = emptyList(),
        expiresOn = today.plusDays(30),
        now = now,
    )

    private fun kid(code: String = "BALANCED") = IssuedKid("doc-1", "sha", code, now)

    @Test
    fun `every core pack carries onboarding rules, and a missing one stops the boot`() {
        assertThat(registry.rules("CZ", ProductLine.DIP, 1).questionnaire.appropriatenessTest).isTrue()
        assertThatThrownBy { StaticOnboardingRulesRegistry(listOf(dps), packs) }
            .hasMessageContaining("without onboarding rules")
    }

    @Test
    fun `signature cannot skip the questionnaire, the document or its acceptance`() {
        val started = application()
        assertThatThrownBy {
            started.sign("c", UUID.randomUUID(), null, today, now)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            started.issueKid("BALANCED", false, kid(), now)
        }.isInstanceOf(IllegalStateException::class.java)
        val issued = started.submitQuestionnaire(
            UUID.randomUUID(),
            "BALANCED",
            now,
        ).issueKid("BALANCED", false, kid(), now)
        assertThatThrownBy {
            issued.sign("c", UUID.randomUUID(), null, today, now)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { issued.acceptKid("another-doc", now) }.isInstanceOf(IllegalArgumentException::class.java)
        val signed = issued.acceptKid("doc-1", now).sign("c", UUID.randomUUID(), null, today.plusDays(14), now)
        assertThat(signed.status).isEqualTo(OnboardingStatus.SIGNED)
    }

    @Test
    fun `re-answering the questionnaire voids the issued document`() {
        val issued = application().submitQuestionnaire(
            UUID.randomUUID(),
            "BALANCED",
            now,
        ).issueKid("BALANCED", false, kid(), now)
        val again = issued.submitQuestionnaire(UUID.randomUUID(), "DYNAMIC", now)
        assertThat(again.kid).isNull()
        assertThatThrownBy { again.acceptKid("doc-1", now) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `activation is refused before the cooling-off period ends, and withdrawal after it`() {
        val signed = application().submitQuestionnaire(UUID.randomUUID(), "BALANCED", now)
            .issueKid("BALANCED", false, kid(), now).acceptKid("doc-1", now)
            .sign("c", UUID.randomUUID(), null, today.plusDays(14), now)
        assertThatThrownBy { signed.activate(today.plusDays(13), now) }.hasMessageContaining("cooling-off")
        assertThat(signed.activate(today.plusDays(14), now).status).isEqualTo(OnboardingStatus.ACTIVATED)
        assertThatThrownBy { signed.withdraw(today.plusDays(15), now) }.hasMessageContaining("cooling-off")
        assertThat(signed.withdraw(today.plusDays(14), now).status).isEqualTo(OnboardingStatus.WITHDRAWN)
    }

    @Test
    fun `only the applicant or their recorded guardian may act`() {
        val guardian = UUID.randomUUID()
        val app = application(guardian)
        assertThat(app.actableBy(party)).isTrue()
        assertThat(app.actableBy(guardian)).isTrue()
        assertThat(app.actableBy(UUID.randomUUID())).isFalse()
    }

    @Test
    fun `the MiFID regime requires knowledge, experience and the ESG preference`() {
        val light = QuestionnaireAnswers(riskAppetite = 2, lossTolerance = 2, financialSituationStable = true)
        assertThatThrownBy {
            SuitabilityAssessment.assess(
                party,
                UUID.randomUUID(),
                ProductLine.DIP,
                dip.questionnaire,
                light,
                today,
                now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        val dpsAssessment = SuitabilityAssessment.assess(
            party,
            UUID.randomUUID(),
            ProductLine.DPS,
            dps.questionnaire,
            light,
            today,
            now,
        )
        assertThat(dpsAssessment.appropriate).isNull()
        val full = light.copy(knowledgeLevel = 0, experienceLevel = 1, esgPreference = EsgPreference.NONE)
        val dipAssessment = SuitabilityAssessment.assess(
            party,
            UUID.randomUUID(),
            ProductLine.DIP,
            dip.questionnaire,
            full,
            today,
            now,
        )
        assertThat(dipAssessment.appropriate).isFalse()
    }

    @Test
    fun `risk profile takes the weaker of appetite and tolerance, capped by unstable finances`() {
        assertThat(
            SuitabilityAssessment.riskProfile(
                QuestionnaireAnswers(riskAppetite = 3, lossTolerance = 1, financialSituationStable = true),
            ).maxRiskClass,
        )
            .isEqualTo(3)
        val capped = SuitabilityAssessment.riskProfile(
            QuestionnaireAnswers(riskAppetite = 3, lossTolerance = 3, financialSituationStable = false),
        )
        assertThat(capped.maxRiskClass).isEqualTo(3)
        assertThat(capped.label).isEqualTo(RiskLabel.BALANCED)
    }

    private fun assessed(appetite: Int, esg: EsgPreference? = null) = SuitabilityAssessment.assess(
        party,
        UUID.randomUUID(),
        ProductLine.DPS,
        dps.questionnaire,
        QuestionnaireAnswers(
            riskAppetite = appetite,
            lossTolerance = appetite,
            financialSituationStable = true,
            esgPreference = esg,
        ),
        today,
        now,
    )

    @Test
    fun `a long horizon and a dynamic profile get the lifecycle default`() {
        val r = StrategyRecommender.recommend(dps, assessed(3), LocalDate.parse("1996-01-01"), 60, today)
        assertThat(r.recommended).isEqualTo("LIFECYCLE")
        assertThat(r.reasons).containsExactly(RecommendationReason.LIFECYCLE_DEFAULT)
    }

    @Test
    fun `a short horizon caps the risk class whatever the appetite`() {
        val r = StrategyRecommender.recommend(dps, assessed(3), LocalDate.parse("1963-01-01"), 60, today)
        assertThat(r.maxRiskClass).isEqualTo(1)
        assertThat(r.recommended).isEqualTo("CONSERVATIVE")
    }

    @Test
    fun `a sustainability preference prefers a sustainable strategy`() {
        val r = StrategyRecommender.recommend(
            dps,
            assessed(1, EsgPreference.CONSIDER),
            LocalDate.parse("1990-01-01"),
            60,
            today,
        )
        assertThat(r.recommended).isEqualTo("SUSTAINABLE_BALANCED")
    }

    private fun transfer(origin: TransferOrigin, direction: TransferDirection = TransferDirection.OUT) =
        TransferRequest.request(
            direction, origin, UUID.randomUUID(), party, Counterparty("P2", "Other", "C-1"), "CZK",
            if (origin == TransferOrigin.PARTICIPANT) "sca-1" else null, today.plusDays(30), now,
        )

    @Test
    fun `a provider-initiated transfer-out waits for the participant's consent`() {
        val requested = transfer(TransferOrigin.RECEIVING_PROVIDER)
        assertThat(requested.status).isEqualTo(TransferStatus.AWAITING_CONSENT)
        assertThatThrownBy {
            requested.valuated(BigDecimal.TEN, BigDecimal.ONE, now)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(requested.consented("sca-2", now).status).isEqualTo(TransferStatus.REQUESTED)
        assertThatThrownBy { transfer(TransferOrigin.RECEIVING_PROVIDER, TransferDirection.IN) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the direction decides the edges`() {
        val out = transfer(TransferOrigin.PARTICIPANT)
        val arrival = FundsArrival(BigDecimal.TEN, "CZK", today, emptyList())
        assertThatThrownBy { out.fundsReceived(arrival, true, now) }.isInstanceOf(IllegalStateException::class.java)
        val inbound = transfer(TransferOrigin.PARTICIPANT, TransferDirection.IN).sent("ref", now)
        assertThatThrownBy {
            inbound.valuated(BigDecimal.TEN, BigDecimal.ZERO, now)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(inbound.fundsReceived(arrival, false, now).incentiveHistory).isEmpty()
    }

    @Test
    fun `the transfer fee applies only before the free period and never exceeds the value`() {
        val rules = TransferRules(allowed = true, freeAfterMonths = 60, maxFee = BigDecimal("800"), deadlineDays = 30)
        assertThat(
            TransferTerms.fee(rules, today.minusMonths(12), BigDecimal("10000"), today),
        ).isEqualByComparingTo("800")
        assertThat(
            TransferTerms.fee(rules, today.minusMonths(60), BigDecimal("10000"), today),
        ).isEqualByComparingTo("0")
        assertThat(TransferTerms.fee(rules, today.minusMonths(1), BigDecimal("300"), today)).isEqualByComparingTo("300")
        assertThat(
            TransferTerms.fee(TransferRules(allowed = true), today, BigDecimal.TEN, today),
        ).isEqualByComparingTo("0")
    }
}
