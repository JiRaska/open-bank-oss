// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.onboarding

import com.openbank.pension.application.onboarding.ChooseStrategyCommand
import com.openbank.pension.application.onboarding.GeneratedDocument
import com.openbank.pension.application.onboarding.KeyInformationDocumentPort
import com.openbank.pension.application.onboarding.OnboardingApplicationRepository
import com.openbank.pension.application.onboarding.OnboardingService
import com.openbank.pension.application.onboarding.ProfileView
import com.openbank.pension.application.onboarding.QuestionnaireService
import com.openbank.pension.application.onboarding.SignatureVerificationPort
import com.openbank.pension.application.onboarding.StartOnboardingCommand
import com.openbank.pension.application.onboarding.SuitabilityAssessmentRepository
import com.openbank.pension.application.onboarding.TransactionRunner
import com.openbank.pension.application.port.out.StrategyNotPermittedException
import com.openbank.pension.application.port.out.StrategySuitabilityRequest
import com.openbank.pension.application.port.out.StrategyWarningsRequiredException
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.ApplicantFacts
import com.openbank.pension.domain.onboarding.EsgPreference
import com.openbank.pension.domain.onboarding.IssuedKid
import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.OnboardingKind
import com.openbank.pension.domain.onboarding.QuestionnaireAnswers
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.questionnaire.InstrumentCompetence
import com.openbank.pension.domain.questionnaire.LocalizedText
import com.openbank.pension.domain.questionnaire.QuestionnaireRecord
import com.openbank.pension.domain.questionnaire.StrategyInstrumentMapping
import com.openbank.pension.domain.questionnaire.StrategyInstrumentMappingPort
import com.openbank.pension.domain.questionnaire.SustainabilityPreference
import com.openbank.pension.domain.questionnaire.WarningCode
import com.openbank.pension.domain.questionnaire.WarningPolicy
import com.openbank.pension.infrastructure.onboarding.pack.OnboardingRulesLoader
import com.openbank.pension.infrastructure.onboarding.pack.QuestionSetLoader
import com.openbank.pension.infrastructure.onboarding.pack.StaticOnboardingRulesRegistry
import com.openbank.pension.infrastructure.onboarding.pack.StaticQuestionSetRegistry
import com.openbank.pension.infrastructure.onboarding.rest.ProfileResponse
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import com.openbank.pension.testsupport.ProviderFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

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
    fun `DPS legacy choice acknowledges pinned warning wording atomically`(): Unit = runBlocking {
        val application = submitted()
        stub(application)
        assertThatThrownBy {
            runBlocking {
                service.chooseStrategy(application.id, party, ChooseStrategyCommand("DYNAMIC", false, null))
            }
        }.hasMessageContaining("STRATEGY_ABOVE_PROFILE")

        coVerify(exactly = 0) { documents.generate(any()) }
        coEvery { documents.generate(any()) } returns GeneratedDocument("doc-1", "sha-doc")
        coEvery { applications.save(any()) } answers { firstArg() }
        val legacyIssued = service.chooseStrategy(application.id, party, ChooseStrategyCommand("DYNAMIC", true, "en"))
        val legacyAck = legacyIssued.warningAcknowledgements.single()
        assertThat(legacyAck.code).isEqualTo(WarningCode.STRATEGY_ABOVE_PROFILE)
        assertThat(legacyAck.language).isEqualTo("en")
        val legacyText = questionSets.questionSet("CZ", ProductLine.DPS)
            .warning(WarningCode.STRATEGY_ABOVE_PROFILE).text.en
        assertThat(legacyAck.textSha256).isEqualTo(WarningPolicy.sha256(legacyText))
        assertThat(legacyIssued.kid?.strategyCode).isEqualTo("DYNAMIC")
        coVerify(exactly = 1) { applications.save(any()) }

        val saved = slot<OnboardingApplication>()
        coEvery { applications.save(capture(saved)) } answers { saved.captured }
        val acknowledged = service.acknowledgeWarnings(
            application.id,
            party,
            "DYNAMIC",
            setOf(WarningCode.STRATEGY_ABOVE_PROFILE),
            "en",
        )
        stub(acknowledged)
        assertThatThrownBy {
            runBlocking { service.chooseStrategy(application.id, party, ChooseStrategyCommand("DYNAMIC", false, "cs")) }
        }.hasMessageContaining("STRATEGY_ABOVE_PROFILE")
        coEvery { documents.generate(any()) } returns GeneratedDocument("doc-1", "sha-doc")
        val issued = service.chooseStrategy(application.id, party, ChooseStrategyCommand("DYNAMIC", false, "en"))
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

    @Test
    fun `a stale warning acknowledgement cannot authorize a choice`(): Unit = runBlocking {
        val application = submitted()
        stub(application)
        val stale = application.acknowledge(
            service.warningAcks(
                application,
                setOf(WarningCode.STRATEGY_ABOVE_PROFILE),
                assessment,
                "DYNAMIC",
                "en",
            ).map { it.copy(textSha256 = WarningPolicy.sha256("previous wording")) },
            now,
        )
        stub(stale)
        assertThatThrownBy {
            runBlocking { service.chooseStrategy(application.id, party, ChooseStrategyCommand("DYNAMIC", true, "en")) }
        }.hasMessageContaining("STRATEGY_ABOVE_PROFILE")
        coVerify(exactly = 0) { documents.generate(any()) }
    }

    @Test
    fun `a newer question set cannot replace wording shown for an existing assessment`(): Unit = runBlocking {
        val older = questionSets.questionSet("CZ", ProductLine.DPS)
        val warning = older.warning(WarningCode.STRATEGY_ABOVE_PROFILE)
        val newer = older.copy(
            version = older.version + 1,
            warnings = older.warnings.map {
                if (it.code == warning.code) {
                    it.copy(text = LocalizedText(it.text.cs + " updated", it.text.en + " updated"))
                } else {
                    it
                }
            },
        )
        val registry = StaticQuestionSetRegistry(QuestionSetLoader.loadAll() + newer, OnboardingRulesLoader.loadAll())
        val pinned = assessment.copy(
            questionnaire = QuestionnaireRecord(
                older.id, older.version, emptyMap(), 3, emptyList(), emptyList(),
                SustainabilityPreference.NONE, null, null, null, emptyList(),
            ),
        )
        val versionedService = OnboardingService(
            applications, assessments, mockk(), mockk(), packs, rules, mockk(), mockk(), documents, signatures,
            mockk(), mockk(), clock, registry, ProviderFixtures.boundary,
        )
        val application = submitted()
        stub(application)
        coEvery { assessments.findById(assessment.id) } returns pinned
        val displayed = QuestionnaireService(versionedService, registry, packs, clock)
            .requiredWarnings(application.id, party, "DYNAMIC").first
        assertThat(displayed.version).isEqualTo(older.version)
        assertThat(displayed.warning(warning.code).text.en).isEqualTo(warning.text.en)

        coEvery { applications.save(any()) } answers { firstArg() }
        val acknowledged = versionedService.acknowledgeWarnings(
            application.id,
            party,
            "DYNAMIC",
            setOf(warning.code),
            "en",
        )
        val ack = acknowledged.warningAcknowledgements.single()
        assertThat(ack.textSha256).isEqualTo(WarningPolicy.sha256(warning.text.en))
        assertThat(ack.textSha256).isNotEqualTo(WarningPolicy.sha256(newer.warning(warning.code).text.en))

        stub(acknowledged)
        coEvery { assessments.findById(assessment.id) } returns pinned
        coEvery { applications.save(any()) } answers { firstArg() }
        coEvery { documents.generate(any()) } returns GeneratedDocument("doc-1", "sha-doc")
        val issued = versionedService.chooseStrategy(
            application.id,
            party,
            ChooseStrategyCommand("DYNAMIC", false, "en"),
        )
        assertThat(issued.chosenStrategy).isEqualTo("DYNAMIC")
    }

    @Test
    fun `pre-mapping DIP assessment cannot choose a strategy or spend SCA`(): Unit = runBlocking {
        val dip = submitted().copy(productLine = ProductLine.DIP)
        val oldAssessment = assessment.copy(productLine = ProductLine.DIP, appropriate = true)
        coEvery { applications.findById(dip.id) } returns dip
        coEvery { assessments.findById(oldAssessment.id) } returns oldAssessment
        assertThatThrownBy {
            runBlocking { service.chooseStrategy(dip.id, party, ChooseStrategyCommand("BALANCED", false, "en")) }
        }.hasMessageContaining("predates strategy instrument mapping")
        coVerify(exactly = 0) { documents.generate(any()) }

        val bypassed = dip.issueKid("BALANCED", false, IssuedKid("doc-1", "sha-doc", "BALANCED", now), now)
            .acceptKid("doc-1", now)
        coEvery { applications.findById(dip.id) } returns bypassed
        assertThatThrownBy { runBlocking { service.sign(dip.id, party, "sca-1") } }
            .hasMessageContaining("predates strategy instrument mapping")
        coVerify(exactly = 0) { signatures.verify(any(), any(), any(), any()) }
    }

    @Test
    fun `legacy DIP scales cannot create an assessment without per-class evidence`(): Unit = runBlocking {
        val dip = submitted().copy(productLine = ProductLine.DIP)
        coEvery { applications.findById(dip.id) } returns dip
        assertThatThrownBy {
            runBlocking {
                service.submitQuestionnaire(
                    dip.id,
                    party,
                    QuestionnaireAnswers(
                        knowledgeLevel = 3,
                        experienceLevel = 3,
                        riskAppetite = 1,
                        lossTolerance = 1,
                        financialSituationStable = true,
                        esgPreference = EsgPreference.NONE,
                    ),
                )
            }
        }.hasMessageContaining("versioned questionnaire is required")
        coVerify(exactly = 0) { assessments.save(any()) }
    }

    @Test
    fun `warning preview uses selected strategy class rather than aggregate score`(): Unit = runBlocking {
        val mapping = StrategyInstrumentMapping("CZ", ProductLine.DIP, "BALANCED", "revision-7", setOf("EQUITY"))
        val dip = submitted().copy(productLine = ProductLine.DIP)
        val assessed = assessment.copy(
            productLine = ProductLine.DIP,
            appropriate = true,
            questionnaire = QuestionnaireRecord(
                "cz-dip-questionnaire", 1, emptyMap(), 3, emptyList(),
                listOf(InstrumentCompetence("EQUITY", 0, 0)),
                SustainabilityPreference.NONE, null, null, null, emptyList(),
            ),
            strategyInstrumentMappings = listOf(mapping),
        )
        val catalog = mockk<StrategyInstrumentMappingPort>()
        coEvery { catalog.effectivePublished("CZ", ProductLine.DIP, "BALANCED", any()) } returns listOf(mapping)
        coEvery { applications.findById(dip.id) } returns dip
        coEvery { assessments.findById(assessed.id) } returns assessed
        val mappedService = OnboardingService(
            applications, assessments, mockk(), mockk(), packs, rules, mockk(), mockk(), documents, signatures,
            mockk(), mockk(), clock, questionSets, ProviderFixtures.boundary, catalog,
        )
        assertThat(mappedService.requiredWarnings(dip.id, party, "BALANCED"))
            .contains(WarningCode.PRODUCT_NOT_APPROPRIATE)
        val recommendation = mappedService.recommendation(dip.id, party)
        val profile = ProfileResponse.from(
            ProfileView(
                assessed,
                questionSets.questionSet("CZ", ProductLine.DIP),
                recommendation,
                setOf(WarningCode.PRODUCT_NOT_APPROPRIATE),
            ),
            "en",
        )
        assertThat(profile.appropriate).isNull()
        assertThatThrownBy {
            runBlocking { mappedService.chooseStrategy(dip.id, party, ChooseStrategyCommand("BALANCED", true, "en")) }
        }.hasMessageContaining("PRODUCT_NOT_APPROPRIATE")
        coVerify(exactly = 0) { documents.generate(any()) }
    }

    @Test
    fun `contract DIP choice uses classes and rejects missing mapping`(): Unit = runBlocking {
        val contractId = UUID.randomUUID()
        val mapping = StrategyInstrumentMapping(
            "CZ",
            ProductLine.DIP,
            "BALANCED",
            "revision-7",
            setOf("BOND_FUNDS", "EQUITY_FUNDS"),
        )
        val dip = submitted().copy(productLine = ProductLine.DIP)
        val assessed = assessment.copy(
            productLine = ProductLine.DIP,
            appropriate = true,
            questionnaire = QuestionnaireRecord(
                "cz-dip-q1", 1, emptyMap(), 3, emptyList(),
                listOf(
                    InstrumentCompetence("BOND_FUNDS", 3, 3),
                    InstrumentCompetence("EQUITY_FUNDS", 0, 0),
                ),
                SustainabilityPreference.NONE, null, null, null, emptyList(),
            ),
            strategyInstrumentMappings = listOf(mapping),
        )
        val catalog = mockk<StrategyInstrumentMappingPort>()
        coEvery { catalog.effectivePublished("CZ", ProductLine.DIP, "BALANCED", any()) } returns listOf(mapping)
        coEvery { applications.findByContract(contractId) } returns dip
        coEvery { assessments.findById(assessed.id) } returns assessed
        val mappedService = OnboardingService(
            applications, assessments, mockk(), mockk(), packs, rules, mockk(), mockk(), documents, signatures,
            mockk(), mockk(), clock, questionSets, ProviderFixtures.boundary, catalog,
        )
        val request = StrategySuitabilityRequest(contractId, "CZ", ProductLine.DIP, 1, "BALANCED", emptySet(), "en")
        assertThatThrownBy { runBlocking { mappedService.authorizeStrategy(request) } }
            .isInstanceOf(StrategyWarningsRequiredException::class.java)
            .hasMessageContaining("PRODUCT_NOT_APPROPRIATE")
        coEvery { catalog.effectivePublished("CZ", ProductLine.DIP, "BALANCED", any()) } returns emptyList()
        assertThatThrownBy { runBlocking { mappedService.authorizeStrategy(request) } }
            .hasMessageContaining("missing or ambiguous")
    }

    @Test
    fun `DIP draft cannot start without reviewed mapping and per-class assessment`(): Unit = runBlocking {
        val request = StrategySuitabilityRequest(null, "CZ", ProductLine.DIP, 1, "BALANCED", emptySet(), "en")
        assertThatThrownBy { runBlocking { service.authorizeStrategy(request) } }
            .isInstanceOf(StrategyNotPermittedException::class.java)
            .hasMessageContaining("approved instrument mapping")
    }

    @Test
    fun `activated DIP reassessment pins reviewed classes for every offered strategy`(): Unit = runBlocking {
        val active = submitted().copy(
            productLine = ProductLine.DIP,
            status = com.openbank.pension.domain.onboarding.OnboardingStatus.ACTIVATED,
            contractId = UUID.randomUUID(),
        )
        val fresh = assessment.copy(
            id = UUID.randomUUID(),
            productLine = ProductLine.DIP,
            questionnaire = QuestionnaireRecord(
                "cz-dip-q1", 1, emptyMap(), 3, emptyList(),
                listOf(InstrumentCompetence("BOND_FUNDS", 3, 3)),
                SustainabilityPreference.NONE, null, null, null, emptyList(),
            ),
        )
        val catalog = mockk<StrategyInstrumentMappingPort>()
        coEvery { catalog.effectivePublished("CZ", ProductLine.DIP, any(), any()) } answers {
            listOf(StrategyInstrumentMapping("CZ", ProductLine.DIP, thirdArg(), "revision-7", setOf("BOND_FUNDS")))
        }
        coEvery { applications.findById(active.id) } returns active
        coEvery { assessments.findById(assessment.id) } returns assessment
        coEvery { assessments.save(any()) } answers { firstArg() }
        coEvery { applications.save(any()) } answers { firstArg() }
        val transaction = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }
        val mappedService = OnboardingService(
            applications, assessments, mockk(), mockk(), packs, rules, mockk(), mockk(), documents, signatures,
            mockk(), transaction, clock, questionSets, ProviderFixtures.boundary, catalog,
        )
        val saved = mappedService.reassess(active.id, party) { _, _ -> fresh }
        assertThat(saved.first.assessmentId).isEqualTo(fresh.id)
        coVerify(exactly = 1) {
            assessments.save(
                match {
                    it.id == fresh.id &&
                        it.strategyInstrumentMappings?.size == rules.rules("CZ", ProductLine.DIP, 1).strategies.size
                },
            )
        }
    }
}
