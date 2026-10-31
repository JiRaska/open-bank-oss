// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.pension.application.onboarding.ProfileView
import com.openbank.pension.application.onboarding.QuestionnaireView
import com.openbank.pension.domain.onboarding.QuestionnaireRegime
import com.openbank.pension.domain.onboarding.RiskLabel
import com.openbank.pension.domain.questionnaire.Dimension
import com.openbank.pension.domain.questionnaire.Inconsistency
import com.openbank.pension.domain.questionnaire.QuestionSet
import com.openbank.pension.domain.questionnaire.SustainabilityCategory
import com.openbank.pension.domain.questionnaire.WarningCode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// --- requests (every field nullable: absent input is a 400, never a 500 — #3104) ----------------

data class QuestionnaireDraftRequest(val answers: Map<String, String?>? = null)

data class AcknowledgeWarningsRequest(
    val strategyCode: String? = null,
    val warnings: List<WarningCode?>? = null,
    val language: String? = null,
)

internal fun Map<String, String?>.requireAnswers(): Map<String, String> =
    mapValues { (k, v) -> requireNotNull(v?.takeIf { it.isNotBlank() }) { "answer to $k must not be blank" } }

// --- responses --------------------------------------------------------------------------------

data class AnswerOptionResponse(val code: String, val text: String, val illustrationCzk: BigDecimal?)

data class QuestionResponse(
    val id: String,
    val dimension: Dimension,
    val instrumentClass: String?,
    val text: String,
    val help: String?,
    val required: Boolean,
    val options: List<AnswerOptionResponse>,
)

data class QuestionStepResponse(
    val code: String,
    val title: String,
    val optional: Boolean,
    val questions: List<QuestionResponse>,
)

data class InconsistencyResponse(val code: String, val questions: List<String>, val message: String) {
    companion object {
        fun from(i: Inconsistency, lang: String?) = InconsistencyResponse(i.code, i.questions, i.message.text(lang))
    }
}

data class ProgressResponse(
    val answered: Int,
    val required: Int,
    val missingRequired: List<String>,
    val nextStep: String?,
    val complete: Boolean,
)

data class PrefillResponse(
    val birthDate: LocalDate,
    val yearsToRetirement: Int,
    val retirementAge: Int,
    val previousAnswersAvailable: Boolean,
)

data class QuestionnaireViewResponse(
    val questionSetId: String,
    val questionSetVersion: Int,
    val legalReviewStatus: String,
    val steps: List<QuestionStepResponse>,
    val answers: Map<String, String>,
    val progress: ProgressResponse,
    val prefill: PrefillResponse,
    val inconsistencies: List<InconsistencyResponse>,
) {
    companion object {
        fun from(v: QuestionnaireView, lang: String?) = QuestionnaireViewResponse(
            questionSetId = v.questionSet.id,
            questionSetVersion = v.questionSet.version,
            legalReviewStatus = v.questionSet.legalReview.status.name,
            steps = steps(v.questionSet, lang, v.lossIllustrationCzk),
            answers = v.answers,
            progress = ProgressResponse(
                v.progress.answered,
                v.progress.required,
                v.progress.missingRequired,
                v.progress.nextStep,
                v.progress.complete,
            ),
            prefill = PrefillResponse(
                v.prefill.birthDate,
                v.prefill.yearsToRetirement,
                v.prefill.retirementAge,
                v.prefill.previousAnswers.isNotEmpty(),
            ),
            inconsistencies = v.inconsistencies.map { InconsistencyResponse.from(it, lang) },
        )

        private fun steps(set: QuestionSet, lang: String?, illustration: Map<String, BigDecimal>) = set.steps.map { s ->
            QuestionStepResponse(
                code = s.code,
                title = s.title.text(lang),
                optional = s.optional,
                questions = set.questions.filter { it.step == s.code }.map { q ->
                    QuestionResponse(
                        id = q.id,
                        dimension = q.dimension,
                        instrumentClass = q.instrumentClass,
                        text = q.text.text(lang),
                        help = q.help?.text(lang),
                        required = q.required && !s.optional,
                        options = q.options.map { o ->
                            AnswerOptionResponse(o.code, o.text.text(lang), illustration["${q.id}:${o.code}"])
                        },
                    )
                },
            )
        }
    }
}

/** One answer that set the risk ceiling — the plain "why" the client shows. */
data class ProfileReasonResponse(
    val dimension: Dimension,
    val questionId: String,
    val question: String,
    val answer: String,
    val maxRiskClass: Int,
)

data class CompetenceResponse(val instrumentClass: String, val knowledge: Int, val experience: Int)

data class SustainabilityResponse(
    val optionCode: String?,
    val categories: Set<SustainabilityCategory>,
    val minSharePercent: Int?,
)

data class WarningResponse(val code: WarningCode, val text: String)

data class ProfileResponse(
    val assessmentId: UUID,
    val questionSetId: String?,
    val questionSetVersion: Int?,
    val riskClass: Int,
    val riskLabel: RiskLabel,
    val why: List<ProfileReasonResponse>,
    val financialSituationStable: Boolean,
    val appropriate: Boolean?,
    val competence: List<CompetenceResponse>,
    val sustainability: SustainabilityResponse?,
    val savingsCzk: BigDecimal?,
    val lossSharePercent: Int?,
    val lossCapacityCzk: BigDecimal?,
    val confirmedInconsistencies: List<String>,
    val recommendation: RecommendationResponse,
    val recommendedStrategyWarnings: List<WarningResponse>,
    val assessedAt: Instant,
    val validUntil: LocalDate,
) {
    companion object {
        fun from(p: ProfileView, lang: String?): ProfileResponse {
            val a = p.assessment
            val record = a.questionnaire
            return ProfileResponse(
                assessmentId = a.id,
                questionSetId = record?.questionSetId,
                questionSetVersion = record?.questionSetVersion,
                riskClass = a.riskProfile.maxRiskClass,
                riskLabel = a.riskProfile.label,
                why = record?.bindingReasons.orEmpty().mapNotNull { r ->
                    val q = p.questionSet.question(r.questionId) ?: return@mapNotNull null
                    val o = q.option(r.optionCode) ?: return@mapNotNull null
                    ProfileReasonResponse(
                        r.dimension,
                        r.questionId,
                        q.text.text(lang),
                        o.text.text(lang),
                        r.maxRiskClass,
                    )
                },
                financialSituationStable = a.answers.financialSituationStable,
                // Appropriateness is strategy-specific once a catalog mapping is pinned.
                // The profile has no selected strategy, so the aggregate best-class score is not an answer.
                appropriate = if (a.strategyInstrumentMappings != null ||
                    a.regime == QuestionnaireRegime.MIFID_SUITABILITY
                ) {
                    null
                } else {
                    a.appropriate
                },
                competence = record?.competence.orEmpty().map {
                    CompetenceResponse(it.instrumentClass, it.knowledge, it.experience)
                },
                sustainability = record?.sustainability?.let {
                    SustainabilityResponse(it.optionCode, it.categories, it.minSharePercent)
                },
                savingsCzk = record?.savingsCzk,
                lossSharePercent = record?.lossSharePercent,
                lossCapacityCzk = record?.lossCapacityCzk,
                confirmedInconsistencies = record?.confirmedInconsistencies.orEmpty(),
                recommendation = RecommendationResponse.from(p.recommendation),
                recommendedStrategyWarnings = p.recommendedStrategyWarnings.map {
                    WarningResponse(it, p.questionSet.warning(it).text.text(lang))
                },
                assessedAt = a.assessedAt,
                validUntil = a.validUntil,
            )
        }
    }
}
