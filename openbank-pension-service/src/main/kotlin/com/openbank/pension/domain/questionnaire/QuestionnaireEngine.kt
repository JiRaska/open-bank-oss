// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.questionnaire

import com.openbank.pension.domain.onboarding.EsgPreference
import com.openbank.pension.domain.onboarding.QuestionnaireAnswers
import com.openbank.pension.domain.onboarding.RiskLabel
import com.openbank.pension.domain.onboarding.RiskProfile
import com.openbank.pension.domain.onboarding.StrategyOption
import java.math.BigDecimal
import java.math.RoundingMode

/** A pair of answers the participant is asked to review (ESMA consistency check). */
data class Inconsistency(val code: String, val questions: List<String>, val message: LocalizedText)

/** The sustainability preference as recorded: plain choice -> regulatory categories. */
data class SustainabilityPreference(
    val optionCode: String?,
    val categories: Set<SustainabilityCategory>,
    val minSharePercent: Int?,
) {
    /** The coarse preference the strategy recommender works with. */
    val legacy: EsgPreference
        get() = when {
            categories.any { it != SustainabilityCategory.PAI_CONSIDERED } -> EsgPreference.REQUIRED
            categories.isNotEmpty() -> EsgPreference.CONSIDER
            else -> EsgPreference.NONE
        }

    companion object {
        val NONE = SustainabilityPreference(null, emptySet(), null)
    }
}

/** One machine-readable reason the client UI turns into a plain sentence ("why this class"). */
data class ProfileReason(
    val dimension: Dimension,
    val questionId: String,
    val optionCode: String,
    val maxRiskClass: Int,
)

/** Knowledge and experience for one instrument class (MiFID appropriateness, DIP). */
data class InstrumentCompetence(val instrumentClass: String, val knowledge: Int, val experience: Int)

/**
 * The result of scoring a complete set of answers. [riskClass] is the 1..7 SRI-aligned ceiling;
 * [bindingReasons] are the answers that set it (the weakest link), which is the "why" shown to the
 * participant. [legacyAnswers] feeds the existing [com.openbank.pension.domain.onboarding.SuitabilityAssessment].
 */
data class QuestionnaireProfile(
    val riskClass: Int,
    val label: RiskLabel,
    val bindingReasons: List<ProfileReason>,
    val financialSituationStable: Boolean,
    val competence: List<InstrumentCompetence>,
    val sustainability: SustainabilityPreference,
    val savingsCzk: BigDecimal?,
    val lossSharePercent: Int?,
    val lossCapacityCzk: BigDecimal?,
    val inconsistencies: List<Inconsistency>,
    val legacyAnswers: QuestionnaireAnswers,
) {
    val riskProfile: RiskProfile get() = RiskProfile(riskClass, label)
}

/** How far a (possibly partial) set of answers is. */
data class QuestionnaireProgress(
    val answered: Int,
    val required: Int,
    val missingRequired: List<String>,
    val nextStep: String?,
) {
    val complete: Boolean get() = missingRequired.isEmpty()
}

/**
 * Scores answers against a [QuestionSet]. Pure: no clock, no I/O, so the golden cases are exact.
 */
@Suppress("TooManyFunctions")
object QuestionnaireEngine {

    private const val UNSTABLE_CAP = 3
    private const val BALANCED_FROM = 3
    private const val DYNAMIC_FROM = 5
    private const val PERCENT = 100

    /**
     * Refuses an unknown question or option (400). A partial answer set is valid; [requireComplete]
     * additionally refuses a missing required answer. The sustainability step is optional as a
     * whole, so its absence is recorded as "no preference", never inferred.
     */
    fun validate(set: QuestionSet, answers: Map<String, String>, requireComplete: Boolean) {
        answers.forEach { (questionId, optionCode) ->
            val question = requireNotNull(set.question(questionId)) { "unknown question $questionId" }
            requireNotNull(question.option(optionCode)) { "unknown option $optionCode for question $questionId" }
        }
        if (requireComplete) {
            val missing = progress(set, answers).missingRequired
            require(missing.isEmpty()) { "required questions are unanswered: ${missing.joinToString()}" }
        }
    }

    fun progress(set: QuestionSet, answers: Map<String, String>): QuestionnaireProgress {
        val optionalSteps = set.steps.filter { it.optional }.map { it.code }.toSet()
        val required = set.questions.filter { it.required && it.step !in optionalSteps }
        val missing = required.filter { it.id !in answers }.map { it.id }
        val nextStep = set.steps.firstOrNull { step ->
            set.questions.any { it.step == step.code && it.id !in answers && (it.required || step.optional) }
        }?.code
        return QuestionnaireProgress(answers.count { set.question(it.key) != null }, required.size, missing, nextStep)
    }

    fun inconsistencies(set: QuestionSet, answers: Map<String, String>): List<Inconsistency> =
        set.consistencyRules.filter { rule ->
            answers[rule.question] in rule.options && answers[rule.conflictsWith] in rule.conflictingOptions
        }.map { Inconsistency(it.code, listOf(it.question, it.conflictsWith), it.message) }

    /** Loss-capacity options illustrated in money against the answered savings band (CZK). */
    fun lossIllustration(set: QuestionSet, answers: Map<String, String>): Map<String, BigDecimal> {
        val savings = savings(set, answers) ?: return emptyMap()
        return set.questions.filter { it.dimension == Dimension.LOSS_CAPACITY }
            .flatMap { q ->
                q.options.map { "${q.id}:${it.code}" to share(savings, checkNotNull(it.lossSharePercent)) }
            }
            .toMap()
    }

    /** Scores a COMPLETE answer set. */
    fun profile(set: QuestionSet, answers: Map<String, String>): QuestionnaireProfile {
        validate(set, answers, requireComplete = true)
        val chosen = answers.mapNotNull { (qid, code) ->
            val q = checkNotNull(set.question(qid))
            q to checkNotNull(q.option(code))
        }
        val riskBearing = chosen.filter { (q, _) -> q.dimension in Question.RISK_BEARING }
        val stable = chosen.filter { (q, _) -> q.dimension == Dimension.FINANCIAL_SITUATION }
            .all { (_, o) -> o.stable != false }
        var ceiling =
            riskBearing.minOfOrNull { (_, o) -> checkNotNull(o.maxRiskClass) } ?: StrategyOption.MAX_RISK_CLASS
        if (!stable) ceiling = minOf(ceiling, UNSTABLE_CAP)
        val binding = riskBearing.filter { (_, o) -> o.maxRiskClass == ceiling }
            .map { (q, o) -> ProfileReason(q.dimension, q.id, o.code, ceiling) }
        val competence = instrumentClasses(set).map { cls ->
            InstrumentCompetence(
                cls,
                knowledge = scoreOf(chosen, Dimension.KNOWLEDGE, cls),
                experience = scoreOf(chosen, Dimension.EXPERIENCE, cls),
            )
        }
        val sustainability = chosen.firstOrNull { (q, _) -> q.dimension == Dimension.SUSTAINABILITY }
            ?.let { (_, o) ->
                val m = checkNotNull(o.sustainability)
                SustainabilityPreference(o.code, m.categories, m.minSharePercent)
            } ?: SustainabilityPreference.NONE
        val savings = savings(set, answers)
        val lossShare = chosen.firstOrNull { (q, _) ->
            q.dimension == Dimension.LOSS_CAPACITY
        }?.second?.lossSharePercent
        return QuestionnaireProfile(
            riskClass = ceiling,
            label = label(ceiling),
            bindingReasons = binding,
            financialSituationStable = stable,
            competence = competence,
            sustainability = sustainability,
            savingsCzk = savings,
            lossSharePercent = lossShare,
            lossCapacityCzk = if (savings != null && lossShare != null) share(savings, lossShare) else null,
            inconsistencies = inconsistencies(set, answers),
            legacyAnswers = legacy(chosen, competence, stable, sustainability, set),
        )
    }

    fun label(riskClass: Int): RiskLabel = when {
        riskClass >= DYNAMIC_FROM -> RiskLabel.DYNAMIC
        riskClass >= BALANCED_FROM -> RiskLabel.BALANCED
        else -> RiskLabel.CONSERVATIVE
    }

    private fun instrumentClasses(set: QuestionSet): List<String> =
        set.questions.mapNotNull { it.instrumentClass }.distinct()

    private fun scoreOf(chosen: List<Pair<Question, AnswerOption>>, dimension: Dimension, cls: String): Int =
        chosen.firstOrNull { (q, _) -> q.dimension == dimension && q.instrumentClass == cls }?.second?.score ?: 0

    private fun savings(set: QuestionSet, answers: Map<String, String>): BigDecimal? {
        val q = set.questions.firstOrNull { it.dimension == Dimension.SAVINGS } ?: return null
        return answers[q.id]?.let { q.option(it)?.amountCzk }
    }

    private fun share(amount: BigDecimal, percent: Int): BigDecimal =
        amount.multiply(BigDecimal(percent)).divide(BigDecimal(PERCENT), 0, RoundingMode.HALF_DOWN)

    /**
     * The 0..3 projection the existing assessment stores. Knowledge/experience take the BEST
     * instrument class (appropriateness asks whether the participant understands the product's
     * main class at all); null when the set asks none, so the light regime keeps omitting them.
     */
    private fun legacy(
        chosen: List<Pair<Question, AnswerOption>>,
        competence: List<InstrumentCompetence>,
        stable: Boolean,
        sustainability: SustainabilityPreference,
        set: QuestionSet,
    ): QuestionnaireAnswers {
        fun scale(d: Dimension) = chosen.filter { (q, _) -> q.dimension == d }.minOfOrNull { (_, o) -> o.score }
        val willingness = scale(Dimension.RISK_WILLINGNESS) ?: 0
        val capacity = scale(Dimension.LOSS_CAPACITY) ?: willingness
        val asksSustainability = set.questions.any { it.dimension == Dimension.SUSTAINABILITY }
        return QuestionnaireAnswers(
            knowledgeLevel = competence.maxOfOrNull { it.knowledge },
            experienceLevel = competence.maxOfOrNull { it.experience },
            riskAppetite = willingness.coerceIn(QuestionnaireAnswers.SCALE),
            lossTolerance = capacity.coerceIn(QuestionnaireAnswers.SCALE),
            financialSituationStable = stable,
            esgPreference = if (asksSustainability) sustainability.legacy else null,
        )
    }
}

/**
 * What the data-driven questionnaire adds to an assessment: the exact set and version asked, the
 * raw answers, and what was derived from them. Stored with the assessment so the evidence of what
 * the participant answered and was told survives later changes to the question set.
 */
data class QuestionnaireRecord(
    val questionSetId: String,
    val questionSetVersion: Int,
    val answers: Map<String, String>,
    val riskClass: Int,
    val bindingReasons: List<ProfileReason>,
    val competence: List<InstrumentCompetence>,
    val sustainability: SustainabilityPreference,
    val savingsCzk: BigDecimal?,
    val lossSharePercent: Int?,
    val lossCapacityCzk: BigDecimal?,
    /** Inconsistencies the participant reviewed and confirmed; the conservative answer bounds the class. */
    val confirmedInconsistencies: List<String>,
)
