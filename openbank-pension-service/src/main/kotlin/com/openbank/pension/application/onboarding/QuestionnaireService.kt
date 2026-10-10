// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.onboarding

import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.StrategyRecommendation
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.questionnaire.Inconsistency
import com.openbank.pension.domain.questionnaire.QuestionSet
import com.openbank.pension.domain.questionnaire.QuestionSetRegistry
import com.openbank.pension.domain.questionnaire.QuestionnaireEngine
import com.openbank.pension.domain.questionnaire.QuestionnaireProgress
import com.openbank.pension.domain.questionnaire.QuestionnaireRecord
import com.openbank.pension.domain.questionnaire.ReassessmentPolicy
import com.openbank.pension.domain.questionnaire.WarningCode
import com.openbank.pension.domain.questionnaire.WarningPolicy
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.util.UUID

/** Final answers contradict each other and the participant has not confirmed them (422). */
class InconsistentAnswersException(val inconsistencies: List<Inconsistency>) :
    RuntimeException("answers need review: ${inconsistencies.joinToString { it.code }}")

/**
 * What the bank already knows, so it is not asked again (issue #12384 "don't ask what we know").
 * The horizon comes from the birth date pinned at start (KYC-verified where KYC had one).
 */
data class QuestionnairePrefill(
    val birthDate: LocalDate,
    val yearsToRetirement: Int,
    val retirementAge: Int,
    /** Answers carried over from the application's previous assessment, as the draft's default. */
    val previousAnswers: Map<String, String>,
)

data class QuestionnaireView(
    val questionSet: QuestionSet,
    val answers: Map<String, String>,
    val progress: QuestionnaireProgress,
    val prefill: QuestionnairePrefill,
    val inconsistencies: List<Inconsistency>,
    /** `questionId:optionCode` -> CZK the option means against the answered savings band. */
    val lossIllustrationCzk: Map<String, BigDecimal>,
)

data class ProfileView(
    val assessment: SuitabilityAssessment,
    val questionSet: QuestionSet,
    val recommendation: StrategyRecommendation,
    /** Warnings choosing the recommended strategy would require (usually none). */
    val recommendedStrategyWarnings: Set<WarningCode>,
)

/**
 * The data-driven questionnaire (issue #12384): question set + prefill + draft (save-and-resume),
 * final submission with consistency checks, and the profile with its explanation. Persistence and
 * the lifecycle stay in [OnboardingService]; this class adds no state of its own.
 */
class QuestionnaireService(
    private val onboarding: OnboardingService,
    private val questionSets: QuestionSetRegistry,
    private val packs: JurisdictionPackRegistry,
    private val clock: Clock,
) {

    private fun today(): LocalDate = LocalDate.now(clock)

    suspend fun view(id: UUID, partyId: UUID): QuestionnaireView {
        val application = onboarding.get(id, partyId)
        return view(application, onboarding.assessmentOf(id, partyId))
    }

    suspend fun saveDraft(id: UUID, partyId: UUID, answers: Map<String, String>): QuestionnaireView {
        val application = onboarding.get(id, partyId)
        QuestionnaireEngine.validate(setFor(application), answers, requireComplete = false)
        val saved = onboarding.saveDraft(id, partyId, answers)
        return view(saved, onboarding.assessmentOf(id, partyId))
    }

    /**
     * Scores the COMPLETE answers. Contradictory answers are returned for review (422) unless the
     * participant confirmed exactly those; a confirmed contradiction is recorded and the more
     * conservative answer bounds the class (the engine takes the weakest answer anyway).
     */
    suspend fun submit(
        id: UUID,
        partyId: UUID,
        answers: Map<String, String>,
        confirmedInconsistencies: Set<String>,
    ): Pair<OnboardingApplication, StrategyRecommendation> {
        val application = onboarding.get(id, partyId)
        val set = setFor(application)
        val profile = QuestionnaireEngine.profile(set, answers)
        val unconfirmed = profile.inconsistencies.filter { it.code !in confirmedInconsistencies }
        if (unconfirmed.isNotEmpty()) throw InconsistentAnswersException(unconfirmed)
        val record = QuestionnaireRecord(
            questionSetId = set.id,
            questionSetVersion = set.version,
            answers = answers,
            riskClass = profile.riskClass,
            bindingReasons = profile.bindingReasons,
            competence = profile.competence,
            sustainability = profile.sustainability,
            savingsCzk = profile.savingsCzk,
            lossSharePercent = profile.lossSharePercent,
            lossCapacityCzk = profile.lossCapacityCzk,
            confirmedInconsistencies = profile.inconsistencies.map { it.code },
        )
        val reassessment = application.status == com.openbank.pension.domain.onboarding.OnboardingStatus.ACTIVATED
        // The draft is onboarding's save-and-resume; a re-assessment is scored from the answers directly.
        if (!reassessment) onboarding.saveDraft(id, partyId, answers)
        // An activated application is a RE-assessment of its contract (strategy change, expiry,
        // life event): same scoring, lifecycle unchanged.
        val submit: suspend (
            UUID,
            UUID,
            (OnboardingApplication, com.openbank.pension.domain.onboarding.OnboardingRules) -> SuitabilityAssessment,
        ) -> Pair<OnboardingApplication, StrategyRecommendation> =
            if (reassessment) {
                onboarding::reassess
            } else {
                onboarding::submitAssessment
            }
        return submit(id, partyId) { app, rules ->
            SuitabilityAssessment.fromQuestionnaire(
                partyId,
                app.id,
                app.productLine,
                rules.questionnaire,
                profile,
                record,
                today(),
                clock.instant(),
            )
        }
    }

    suspend fun profile(id: UUID, partyId: UUID): ProfileView {
        val application = onboarding.get(id, partyId)
        val assessment =
            checkNotNull(onboarding.assessmentOf(id, partyId)) { "the questionnaire has not been answered" }
        val refresh = ReassessmentPolicy.refreshReason(assessment, today())
        // A stale profile is never shown as current: the client must answer again (409).
        check(refresh == null) { "the questionnaire answers are stale ($refresh); answer it again" }
        val recommendation = onboarding.recommendation(id, partyId)
        return ProfileView(
            assessment = assessment,
            questionSet = setFor(application),
            recommendation = recommendation,
            recommendedStrategyWarnings = WarningPolicy.required(
                recommendation.recommended,
                assessment,
                recommendation,
            ),
        )
    }

    /** The warnings choosing [strategyCode] would require, so the UI can show them before the choice. */
    suspend fun requiredWarnings(id: UUID, partyId: UUID, strategyCode: String): Pair<QuestionSet, Set<WarningCode>> {
        val application = onboarding.get(id, partyId)
        require(onboarding.offers(application, strategyCode)) {
            "strategy $strategyCode is not offered under this pack"
        }
        val assessment =
            checkNotNull(onboarding.assessmentOf(id, partyId)) { "the questionnaire has not been answered" }
        val recommendation = onboarding.recommendation(id, partyId)
        return setFor(application) to WarningPolicy.required(strategyCode, assessment, recommendation)
    }

    private fun view(application: OnboardingApplication, previous: SuitabilityAssessment?): QuestionnaireView {
        val set = setFor(application)
        val previousAnswers = previous?.questionnaire
            ?.takeIf { it.questionSetId == set.id && it.questionSetVersion == set.version }
            ?.answers.orEmpty()
        val answers = application.questionnaireDraft.ifEmpty { previousAnswers }
        val retirementAge = packs.pinned(application.jurisdiction, application.productLine, application.packVersion)
            .payout.minAge
        val age = Period.between(application.applicant.birthDate, today()).years
        return QuestionnaireView(
            questionSet = set,
            answers = answers,
            progress = QuestionnaireEngine.progress(set, answers),
            prefill = QuestionnairePrefill(
                birthDate = application.applicant.birthDate,
                yearsToRetirement = maxOf(0, retirementAge - age),
                retirementAge = retirementAge,
                previousAnswers = previousAnswers,
            ),
            inconsistencies = QuestionnaireEngine.inconsistencies(set, answers),
            lossIllustrationCzk = QuestionnaireEngine.lossIllustration(set, answers),
        )
    }

    private fun setFor(application: OnboardingApplication): QuestionSet =
        questionSets.questionSet(application.jurisdiction, application.productLine)
}
