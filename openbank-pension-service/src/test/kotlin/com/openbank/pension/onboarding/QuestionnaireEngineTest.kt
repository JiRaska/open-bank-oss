// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.onboarding

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.AssessmentStatus
import com.openbank.pension.domain.onboarding.EsgPreference
import com.openbank.pension.domain.onboarding.QuestionnaireRegime
import com.openbank.pension.domain.onboarding.QuestionnaireRules
import com.openbank.pension.domain.onboarding.RecommendationReason
import com.openbank.pension.domain.onboarding.RiskLabel
import com.openbank.pension.domain.onboarding.StrategyRecommendation
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.questionnaire.Dimension
import com.openbank.pension.domain.questionnaire.LifeEvent
import com.openbank.pension.domain.questionnaire.QuestionnaireEngine
import com.openbank.pension.domain.questionnaire.QuestionnaireRecord
import com.openbank.pension.domain.questionnaire.ReassessmentPolicy
import com.openbank.pension.domain.questionnaire.RefreshReason
import com.openbank.pension.domain.questionnaire.SustainabilityCategory
import com.openbank.pension.domain.questionnaire.WarningAcknowledgement
import com.openbank.pension.domain.questionnaire.WarningCode
import com.openbank.pension.domain.questionnaire.WarningPolicy
import com.openbank.pension.infrastructure.onboarding.pack.OnboardingRulesLoader
import com.openbank.pension.infrastructure.onboarding.pack.QuestionSetLoader
import com.openbank.pension.infrastructure.onboarding.pack.StaticQuestionSetRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class QuestionnaireEngineTest {

    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val today = LocalDate.parse("2026-10-09")
    private val sets = QuestionSetLoader.loadAll()
    private val onboardingRules = OnboardingRulesLoader.loadAll()
    private val registry = StaticQuestionSetRegistry(sets, onboardingRules)
    private val dps = registry.questionSet("CZ", ProductLine.DPS)
    private val dip = registry.questionSet("CZ", ProductLine.DIP)
    private val dipRules = onboardingRules.first { it.productLine == ProductLine.DIP }
    private val dpsRules = onboardingRules.first { it.productLine == ProductLine.DPS }

    private fun dpsAnswers(
        objective: String = "GROWTH",
        reaction: String = "HOLD",
        capacity: String = "UP_TO_25",
        savings: String? = "50K_250K",
        esg: String? = null,
    ) = buildMap {
        put("dps.objective", objective)
        put("dps.risk_reaction", reaction)
        put("dps.knowledge", "CORRECT")
        put("dps.experience", "OCCASIONALLY")
        put("dps.loss_capacity", capacity)
        savings?.let { put("dps.savings", it) }
        esg?.let { put("dps.sustainability", it) }
    }

    private fun dipAnswers(knowledge: String = "CORRECT", experience: String = "REGULARLY", esg: String? = null) =
        buildMap {
            put("dip.objective", "GROWTH")
            put("dip.financial_situation", "EASILY")
            put("dip.savings", "250K_1M")
            put("dip.loss_capacity", "UP_TO_25")
            put("dip.risk_reaction", "HOLD")
            put("dip.knowledge_bonds", knowledge)
            put("dip.experience_bonds", experience)
            put("dip.knowledge_equity", knowledge)
            put("dip.experience_equity", experience)
            esg?.let { put("dip.sustainability", it) }
        }

    // --- data integrity -----------------------------------------------------------------------

    @Test
    fun `every onboarding pack has a question set of its own regime, marked for legal review`() {
        assertThat(dps.regime).isEqualTo(QuestionnaireRegime.RISK_PROFILE_ONLY)
        assertThat(dip.regime).isEqualTo(QuestionnaireRegime.MIFID_SUITABILITY)
        assertThat(sets).allSatisfy { assertThat(it.legalReview.status.name).isEqualTo("REQUIRES_LEGAL_REVIEW") }
        // The DPS short path stays within five screens (four steps + summary).
        assertThat(dps.steps).hasSizeLessThanOrEqualTo(4)
        assertThat(dps.steps.single { it.code == "SUSTAINABILITY" }.optional).isTrue()
    }

    @Test
    fun `a pack without a question set, or with one of another regime, stops the boot`() {
        assertThatThrownBy { StaticQuestionSetRegistry(listOf(dps), onboardingRules) }
            .hasMessageContaining("no question set for CZ/DIP")
        val wrongRegime = dip.copy(id = "x", regime = QuestionnaireRegime.RISK_PROFILE_ONLY)
        assertThatThrownBy { StaticQuestionSetRegistry(listOf(dps, wrongRegime), onboardingRules) }
            .hasMessageContaining("requires MIFID_SUITABILITY")
    }

    @Test
    fun `registry keeps older versions for rendering stored assessments`() {
        val newer = dip.copy(version = dip.version + 1)
        val versioned = StaticQuestionSetRegistry(sets + newer, onboardingRules)
        assertThat(versioned.questionSet("CZ", ProductLine.DIP)).isEqualTo(newer)
        assertThat(versioned.questionSet(dip.id, dip.version)).isEqualTo(dip)
    }

    @Test
    fun `a MiFID set without a knowledge question, or a set missing warning wording, is refused`() {
        assertThatThrownBy {
            dip.copy(
                questions = dip.questions.filterNot { it.dimension == Dimension.KNOWLEDGE },
                consistencyRules = emptyList(),
            )
        }
            .hasMessageContaining("required KNOWLEDGE question")
        assertThatThrownBy { dps.copy(warnings = dps.warnings.drop(1)) }
            .hasMessageContaining("must word every warning code")
    }

    @Test
    fun `the birth date is not asked - the horizon is derived, never a question`() {
        assertThat(sets.flatMap { it.questions }.map { it.id }).noneMatch {
            "birth" in it ||
                "horizon" in it ||
                "age" in it
        }
    }

    // --- golden scoring cases -------------------------------------------------------------------

    @ParameterizedTest(name = "{0}/{1}/{2} -> class {3} {4}")
    @CsvSource(
        "MAX_GROWTH, BUY_MORE, OVER_25, 7, DYNAMIC",
        "GROWTH,     HOLD,     UP_TO_25, 5, DYNAMIC",
        "GROWTH,     BUY_MORE, OVER_25, 6, DYNAMIC",
        "STEADY,     HOLD,     OVER_25, 4, BALANCED",
        "GROWTH,     SWITCH_SAFER, OVER_25, 3, BALANCED",
        "PRESERVE,   HOLD,     UP_TO_25, 2, CONSERVATIVE",
        "MAX_GROWTH, SELL_ALL, OVER_25, 1, CONSERVATIVE",
        "MAX_GROWTH, BUY_MORE, NONE,    1, CONSERVATIVE",
    )
    fun `the weakest risk-bearing answer sets the class`(
        objective: String,
        reaction: String,
        capacity: String,
        riskClass: Int,
        label: RiskLabel,
    ) {
        val profile = QuestionnaireEngine.profile(dps, dpsAnswers(objective, reaction, capacity))
        assertThat(profile.riskClass).isEqualTo(riskClass)
        assertThat(profile.label).isEqualTo(label)
        assertThat(profile.bindingReasons).isNotEmpty.allSatisfy { assertThat(it.maxRiskClass).isEqualTo(riskClass) }
    }

    @Test
    fun `the explanation names exactly the answers that bind the class`() {
        val profile = QuestionnaireEngine.profile(dps, dpsAnswers("GROWTH", "HOLD", "UP_TO_25"))
        assertThat(profile.bindingReasons.map { it.questionId })
            .containsExactlyInAnyOrder("dps.risk_reaction", "dps.loss_capacity")
    }

    @Test
    fun `an unstable financial situation caps the class at 3 whatever the appetite`() {
        val answers = dipAnswers() + ("dip.financial_situation" to "STRUGGLE")
        val profile = QuestionnaireEngine.profile(dip, answers)
        assertThat(profile.financialSituationStable).isFalse()
        assertThat(profile.riskClass).isEqualTo(3)
        assertThat(profile.legacyAnswers.financialSituationStable).isFalse()
    }

    @Test
    fun `loss capacity is expressed in CZK of the answered savings`() {
        val profile = QuestionnaireEngine.profile(dps, dpsAnswers(capacity = "UP_TO_25", savings = "50K_250K"))
        assertThat(profile.savingsCzk).isEqualByComparingTo("150000")
        assertThat(profile.lossCapacityCzk).isEqualByComparingTo("37500")
        assertThat(QuestionnaireEngine.lossIllustration(dps, mapOf("dps.savings" to "250K_1M")))
            .containsEntry("dps.loss_capacity:UP_TO_10", BigDecimal("60000"))
        // Savings are optional: no band, no illustration and no money figure — never a guess.
        val anonymous = QuestionnaireEngine.profile(dps, dpsAnswers(savings = null))
        assertThat(anonymous.lossCapacityCzk).isNull()
        assertThat(QuestionnaireEngine.lossIllustration(dps, emptyMap())).isEmpty()
    }

    @Test
    fun `knowledge and experience are scored per instrument class and decide appropriateness`() {
        val novice = QuestionnaireEngine.profile(dip, dipAnswers(knowledge = "DONT_KNOW", experience = "NEVER"))
        assertThat(novice.competence.map { it.instrumentClass }).containsExactly("BOND_FUNDS", "EQUITY_FUNDS")
        val assessment = assess(novice)
        assertThat(assessment.appropriate).isFalse()
        val expert = assess(QuestionnaireEngine.profile(dip, dipAnswers()))
        assertThat(expert.appropriate).isTrue()
    }

    @Test
    fun `appropriateness cannot combine knowledge and experience from different instruments`() {
        val crossed = dipAnswers(knowledge = "DONT_KNOW", experience = "NEVER") + mapOf(
            "dip.knowledge_bonds" to "CORRECT",
            "dip.experience_equity" to "PROFESSIONALLY",
        )
        val profile = QuestionnaireEngine.profile(dip, crossed)
        assertThat(profile.competence.map { it.knowledge + it.experience }).containsExactly(3, 3)
        val assessment = assess(profile, dipRules.questionnaire.copy(appropriatenessMinScore = 4))
        assertThat(assessment.appropriate).isFalse()
        assertThat(WarningPolicy.required("BALANCED", assessment, recommendation))
            .containsExactly(WarningCode.PRODUCT_NOT_APPROPRIATE)
    }

    // --- validation, progress, consistency ------------------------------------------------------

    @Test
    fun `partial answers are valid drafts, final answers must be complete and known`() {
        QuestionnaireEngine.validate(dps, mapOf("dps.objective" to "GROWTH"), requireComplete = false)
        assertThatThrownBy {
            QuestionnaireEngine.validate(dps, mapOf("dps.objective" to "GROWTH"), requireComplete = true)
        }.hasMessageContaining("dps.risk_reaction")
        assertThatThrownBy { QuestionnaireEngine.validate(dps, mapOf("dps.objective" to "YOLO"), false) }
            .hasMessageContaining("unknown option")
        assertThatThrownBy { QuestionnaireEngine.validate(dps, mapOf("dps.nope" to "X"), false) }
            .hasMessageContaining("unknown question")
        val progress = QuestionnaireEngine.progress(dps, mapOf("dps.objective" to "GROWTH"))
        assertThat(progress.nextStep).isEqualTo("RISK")
        assertThat(progress.complete).isFalse()
        assertThat(QuestionnaireEngine.progress(dps, dpsAnswers()).complete).isTrue()
    }

    @Test
    fun `contradictory answers are detected, consistent ones are not`() {
        val contradictory = dpsAnswers(objective = "PRESERVE", reaction = "BUY_MORE")
        assertThat(QuestionnaireEngine.inconsistencies(dps, contradictory).map { it.code })
            .containsExactly("OBJECTIVE_VS_REACTION")
        assertThat(
            QuestionnaireEngine.inconsistencies(dps, dpsAnswers(reaction = "HOLD", capacity = "NONE")).map {
                it.code
            },
        )
            .containsExactly("REACTION_VS_CAPACITY")
        assertThat(QuestionnaireEngine.inconsistencies(dps, dpsAnswers())).isEmpty()
        val expertClaim = dipAnswers(knowledge = "WRONG", experience = "REGULARLY")
        assertThat(QuestionnaireEngine.inconsistencies(dip, expertClaim).map { it.code })
            .contains("EQUITY_EXPERIENCE_VS_KNOWLEDGE")
        // A confirmed contradiction still resolves conservatively: the weaker answer bounds.
        assertThat(QuestionnaireEngine.profile(dps, contradictory).riskClass).isEqualTo(2)
    }

    // --- sustainability ----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "NO_PREFERENCE,      NONE,     ''",
        "AVOID_HARM,         CONSIDER, PAI_CONSIDERED",
        "PART_SUSTAINABLE,   REQUIRED, SFDR_SUSTAINABLE",
        "GREEN_TAXONOMY,     REQUIRED, TAXONOMY_ALIGNED",
        "MOSTLY_SUSTAINABLE, REQUIRED, PAI_CONSIDERED;SFDR_SUSTAINABLE",
    )
    fun `plain sustainability choices map to the regulatory categories`(
        option: String,
        legacy: EsgPreference,
        categories: String,
    ) {
        val profile = QuestionnaireEngine.profile(dip, dipAnswers(esg = option))
        val expected = categories.split(";").filter { it.isNotBlank() }.map { SustainabilityCategory.valueOf(it) }
        assertThat(profile.sustainability.categories).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(profile.sustainability.legacy).isEqualTo(legacy)
        assertThat(profile.legacyAnswers.esgPreference).isEqualTo(legacy)
        if (legacy == EsgPreference.REQUIRED) assertThat(profile.sustainability.minSharePercent).isPositive()
    }

    @Test
    fun `skipping the optional sustainability step records no preference, never a guess`() {
        val profile = QuestionnaireEngine.profile(dip, dipAnswers(esg = null))
        assertThat(profile.sustainability.categories).isEmpty()
        assertThat(profile.legacyAnswers.esgPreference).isEqualTo(EsgPreference.NONE)
    }

    // --- warnings ------------------------------------------------------------------------------------

    private fun assess(
        profile: com.openbank.pension.domain.questionnaire.QuestionnaireProfile,
        rules: QuestionnaireRules = dipRules.questionnaire,
    ) = SuitabilityAssessment.fromQuestionnaire(
        UUID.randomUUID(),
        UUID.randomUUID(),
        ProductLine.DIP,
        rules,
        profile,
        QuestionnaireRecord(
            dip.id, dip.version, emptyMap(), profile.riskClass, profile.bindingReasons, profile.competence,
            profile.sustainability, null, null, null, emptyList(),
        ),
        today,
        now,
    )

    private val recommendation =
        StrategyRecommendation("BALANCED", listOf("CONSERVATIVE", "BALANCED"), 3, 25, emptyList())

    @Test
    fun `a riskier strategy needs the warning, which a DPS may override and a MiFID DIP may not`() {
        val assessment = assess(QuestionnaireEngine.profile(dip, dipAnswers()))
        assertThat(WarningPolicy.required("DYNAMIC", assessment, recommendation))
            .containsExactly(WarningCode.STRATEGY_ABOVE_PROFILE)
        assertThat(WarningPolicy.required("BALANCED", assessment, recommendation)).isEmpty()
        assertThat(WarningPolicy.overridable(WarningCode.STRATEGY_ABOVE_PROFILE, dpsRules)).isTrue()
        assertThat(WarningPolicy.overridable(WarningCode.STRATEGY_ABOVE_PROFILE, dipRules)).isFalse()
        assertThat(WarningPolicy.overridable(WarningCode.PRODUCT_NOT_APPROPRIATE, dipRules)).isTrue()
    }

    @Test
    fun `an acknowledgement counts only for its own assessment and strategy`() {
        val assessmentId = UUID.randomUUID()
        val ack = WarningAcknowledgement(
            WarningCode.STRATEGY_ABOVE_PROFILE,
            assessmentId,
            "DYNAMIC",
            WarningPolicy.sha256("current"),
            "cs",
            now,
        )
        val required = setOf(WarningCode.STRATEGY_ABOVE_PROFILE)
        val currentText: (WarningCode, String) -> String = { _, _ -> "current" }
        fun missing(
            acknowledgements: List<WarningAcknowledgement> = listOf(ack),
            id: UUID = assessmentId,
            strategy: String = "DYNAMIC",
            text: (WarningCode, String) -> String = currentText,
            language: String? = null,
        ) = WarningPolicy.missing(required, acknowledgements, id, strategy, text, language)
        assertThat(missing(language = "cs")).isEmpty()
        assertThat(missing(strategy = "EQUITY_GLOBAL")).isEqualTo(required)
        assertThat(missing(id = UUID.randomUUID())).isEqualTo(required)
        assertThat(missing(acknowledgements = emptyList())).isEqualTo(required)
        assertThat(missing(language = "en")).isEqualTo(required)
        assertThat(missing(text = { _, _ -> "revised" })).isEqualTo(required)
    }

    @Test
    fun `an unmet sustainability preference needs its own acknowledgement`() {
        val assessment = assess(QuestionnaireEngine.profile(dip, dipAnswers(esg = "GREEN_TAXONOMY")))
        val noGreen = recommendation.copy(reasons = listOf(RecommendationReason.NO_SUSTAINABLE_STRATEGY_AVAILABLE))
        assertThat(WarningPolicy.required("BALANCED", assessment, noGreen))
            .containsExactly(WarningCode.SUSTAINABILITY_PREFERENCE_NOT_MET)
    }

    // --- re-assessment ---------------------------------------------------------------------------

    @Test
    fun `an assessment goes stale on expiry, a life event, a riskier strategy, or supersession`() {
        val assessment = assess(QuestionnaireEngine.profile(dip, dipAnswers()))
        assertThat(ReassessmentPolicy.refreshReason(assessment, today)).isNull()
        assertThat(ReassessmentPolicy.refreshReason(assessment, assessment.validUntil)).isNull()
        assertThat(ReassessmentPolicy.refreshReason(assessment, assessment.validUntil.plusDays(1)))
            .isEqualTo(RefreshReason.EXPIRED)
        assertThat(ReassessmentPolicy.refreshReason(assessment, today, lifeEvent = LifeEvent.JOB_LOSS))
            .isEqualTo(RefreshReason.LIFE_EVENT)
        assertThat(ReassessmentPolicy.refreshReason(assessment, today, requestedStrategyRiskClass = 7))
            .isEqualTo(RefreshReason.STRATEGY_CHANGE_ABOVE_PROFILE)
        assertThat(ReassessmentPolicy.refreshReason(assessment, today, requestedStrategyRiskClass = 5)).isNull()
        val superseded = assessment.supersede()
        assertThat(superseded.status).isEqualTo(AssessmentStatus.SUPERSEDED)
        assertThat(ReassessmentPolicy.refreshReason(superseded, today)).isEqualTo(RefreshReason.SUPERSEDED)
    }
}
