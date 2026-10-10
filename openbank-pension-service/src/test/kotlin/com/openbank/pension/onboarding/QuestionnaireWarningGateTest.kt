// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.onboarding

import com.openbank.pension.application.onboarding.ChooseStrategyCommand
import com.openbank.pension.application.onboarding.GeneratedDocument
import com.openbank.pension.application.onboarding.KeyInformationDocumentPort
import com.openbank.pension.application.onboarding.OnboardingApplicationRepository
import com.openbank.pension.application.onboarding.OnboardingService
import com.openbank.pension.application.onboarding.SignatureVerificationPort
import com.openbank.pension.application.onboarding.StartOnboardingCommand
import com.openbank.pension.application.onboarding.SuitabilityAssessmentRepository
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.ApplicantFacts
import com.openbank.pension.domain.onboarding.IssuedKid
import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.OnboardingKind
import com.openbank.pension.domain.onboarding.QuestionnaireAnswers
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.questionnaire.WarningCode
import com.openbank.pension.domain.questionnaire.WarningPolicy
import com.openbank.pension.infrastructure.onboarding.pack.OnboardingRulesLoader
import com.openbank.pension.infrastructure.onboarding.pack.QuestionSetLoader
import com.openbank.pension.infrastructure.onboarding.pack.StaticOnboardingRulesRegistry
import com.openbank.pension.infrastructure.onboarding.pack.StaticQuestionSetRegistry
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import com.openbank.pension.testsupport.ProviderFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The warning-acknowledgement gate (issue #12384) through the real [OnboardingService]: a riskier
 * DPS strategy cannot be chosen without an acknowledgement, the acknowledgement records the exact
 * wording shown, and a signature is refused — before the SCA challenge is even spent — when the
 * application reaches it without one.
 */
class QuestionnaireWarningGateTest {

    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val today = LocalDate.parse("2026-10-09")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val packs = JurisdictionPackLoader.loadRegistry()
    private val rules = StaticOnboardingRulesRegistry(OnboardingRulesLoader.loadAll(), packs.all())
    private val questionSets = StaticQuestionSetRegistry(QuestionSetLoader.loadAll(), OnboardingRulesLoader.loadAll())
    private val applications = mockk<OnboardingApplicationRepository>()
    private val assessments = mockk<SuitabilityAssessmentRepository>()
    private val documents = mockk<KeyInformationDocumentPort>()
    private val signatures = mockk<SignatureVerificationPort>()
    private val party = UUID.randomUUID()

    private val service = OnboardingService(
        applications, assessments, mockk(), mockk(), packs, rules, mockk(), mockk(), documents, signatures,
        mockk(), mockk(), clock, questionSets, ProviderFixtures.boundary,
    )

    @Test
    fun `another provider is refused before KYC or persistence`(): Unit = runBlocking {
        val command = StartOnboardingCommand(
            actingPartyId = party,
            onBehalfOfPartyId = null,
            kind = OnboardingKind.NEW_CONTRACT,
            productLine = ProductLine.DPS,
            jurisdiction = "CZ",
            providerEntityId = UUID.randomUUID(),
            providerType = ProviderType.PENSION_COMPANY,
            schedule = ContributionSchedule(BigDecimal("1000"), "CZK", ContributionFrequency.MONTHLY),
            declaredBirthDate = LocalDate.parse("1990-01-01"),
            declaredResidencyCountry = "CZ",
            residencyEvidence = emptySet(),
            ceding = null,
        )
        // All collaborator mocks are strict: reaching KYC or a repository would fail this assertion.
        assertThatThrownBy { runBlocking { service.start(command) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("configured pension provider")
        coVerify(exactly = 0) { applications.save(any()) }
    }

    /** A cautious DPS profile (class 3): DYNAMIC (class 5) is above it. */
    private val assessment = SuitabilityAssessment.assess(
        party,
        UUID.randomUUID(),
        ProductLine.DPS,
        rules.rules("CZ", ProductLine.DPS, 1).questionnaire,
        QuestionnaireAnswers(riskAppetite = 1, lossTolerance = 1, financialSituationStable = true),
        today,
        now,
    )

    private fun submitted(): OnboardingApplication = OnboardingApplication.start(
        partyId = party,
        kind = OnboardingKind.NEW_CONTRACT,
        productLine = ProductLine.DPS,
        jurisdiction = "CZ",
        packVersion = 1,
        providerEntityId = ProviderFixtures.ID,
        providerType = ProviderType.PENSION_COMPANY,
        schedule = ContributionSchedule(BigDecimal("1000"), "CZK", ContributionFrequency.MONTHLY),
        applicant = ApplicantFacts(LocalDate.parse("1990-01-01"), "CZ", emptySet(), true, null),
        ceding = null,
        ineligibilityReasons = emptyList(),
        expiresOn = today.plusDays(30),
        now = now,
    ).submitQuestionnaire(assessment.id, "BALANCED", now)

    private fun stub(application: OnboardingApplication) {
        coEvery { applications.findById(application.id) } returns application
        coEvery { assessments.findById(assessment.id) } returns assessment
    }

    @Test
    fun `a riskier strategy without an acknowledgement is refused, with it the wording is recorded`(): Unit =
        runBlocking {
            val application = submitted()
            stub(application)
            assertThatThrownBy {
                runBlocking {
                    service.chooseStrategy(application.id, party, ChooseStrategyCommand("DYNAMIC", false, null))
                }
            }.hasMessageContaining("STRATEGY_ABOVE_PROFILE")

            coEvery { documents.generate(any()) } returns GeneratedDocument("doc-1", "sha-doc")
            val saved = slot<OnboardingApplication>()
            coEvery { applications.save(capture(saved)) } answers { saved.captured }
            val issued = service.chooseStrategy(application.id, party, ChooseStrategyCommand("DYNAMIC", true, "en"))
            val ack = issued.warningAcknowledgements.single()
            assertThat(ack.code).isEqualTo(WarningCode.STRATEGY_ABOVE_PROFILE)
            assertThat(ack.strategyCode).isEqualTo("DYNAMIC")
            assertThat(ack.assessmentId).isEqualTo(assessment.id)
            val shown = questionSets.questionSet(
                "CZ",
                ProductLine.DPS,
            ).warning(WarningCode.STRATEGY_ABOVE_PROFILE).text.en
            assertThat(ack.textSha256).isEqualTo(WarningPolicy.sha256(shown))
            assertThat(issued.unsuitableChoiceAcknowledged).isTrue()
        }

    @Test
    fun `a signature without the acknowledgement is refused before the SCA challenge is spent`() {
        // Reached only by a path that bypassed chooseStrategy's check — the gate at signature is
        // the defence in depth, so the application is built directly.
        val unacknowledged = submitted()
            .issueKid("DYNAMIC", false, IssuedKid("doc-1", "sha-doc", "DYNAMIC", now), now)
            .acceptKid("doc-1", now)
        stub(unacknowledged)
        assertThatThrownBy { runBlocking { service.sign(unacknowledged.id, party, "sca-1") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("warnings must be acknowledged before signing")
        coVerify(exactly = 0) { signatures.verify(any(), any(), any(), any()) }
    }
}
