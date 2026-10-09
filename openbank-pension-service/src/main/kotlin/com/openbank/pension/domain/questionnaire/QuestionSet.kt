// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.questionnaire

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.QuestionnaireRegime
import com.openbank.pension.domain.onboarding.StrategyOption
import com.openbank.pension.domain.pack.LegalReview
import java.math.BigDecimal

/**
 * The investment questionnaire as versioned DATA (ADR-0334 §4 step 2, issue #12384).
 *
 * One question set per `(jurisdiction, productLine)` and version, loaded from
 * `jurisdiction-packs/questionnaire/`. The Kotlin here knows only GENERIC dimensions — what a
 * question measures — never a country's wording or thresholds: those live in the data, carry a
 * [legalReview] marker, and a jurisdiction is added by adding a file.
 *
 * Design rules the data model enforces (ESMA suitability guidelines 2022):
 *  - closed answer options only, so nothing is scored from free text;
 *  - risk is bounded by EVERY risk-bearing answer ([AnswerOption.maxRiskClass]), so the weakest of
 *    willingness, loss capacity, objective and financial stability decides, never an average;
 *  - knowledge and experience are asked per instrument class with factual options, not as a
 *    self-rating;
 *  - the sustainability step is separate and optional, mapped to the three regulatory categories.
 */
data class QuestionSet(
    val id: String,
    val version: Int,
    val jurisdiction: String,
    val productLine: ProductLine,
    val regime: QuestionnaireRegime,
    val legalReview: LegalReview,
    val steps: List<QuestionStep>,
    val questions: List<Question>,
    val consistencyRules: List<ConsistencyRule> = emptyList(),
    val warnings: List<WarningText> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "question set id must not be blank" }
        require(version >= 1) { "question set version must be >= 1" }
        require(steps.isNotEmpty()) { "question set $id has no steps" }
        require(steps.map { it.code }.toSet().size == steps.size) { "question set $id: step codes must be unique" }
        require(
            questions.map {
                it.id
            }.toSet().size == questions.size,
        ) { "question set $id: question ids must be unique" }
        val stepCodes = steps.map { it.code }.toSet()
        questions.forEach { q ->
            require(q.step in stepCodes) { "question ${q.id} names unknown step ${q.step}" }
        }
        val byId = questions.associateBy { it.id }
        consistencyRules.forEach { rule ->
            listOf(
                rule.question to rule.options,
                rule.conflictsWith to rule.conflictingOptions,
            ).forEach { (qid, codes) ->
                val q = requireNotNull(byId[qid]) { "consistency rule ${rule.code} names unknown question $qid" }
                require(codes.all { q.option(it) != null }) {
                    "consistency rule ${rule.code} names an unknown option of $qid"
                }
            }
        }
        require(questions.count { it.dimension == Dimension.SAVINGS } <= 1) { "at most one SAVINGS question" }
        if (regime == QuestionnaireRegime.MIFID_SUITABILITY) {
            REQUIRED_UNDER_MIFID.forEach { d ->
                require(questions.any { it.dimension == d && it.required }) {
                    "question set $id: a MiFID suitability set must ask a required $d question"
                }
            }
        }
        require(WarningCode.entries.all { code -> warnings.any { it.code == code } }) {
            "question set $id must word every warning code"
        }
    }

    fun question(id: String): Question? = questions.firstOrNull { it.id == id }

    fun warning(code: WarningCode): WarningText = warnings.first { it.code == code }

    private companion object {
        val REQUIRED_UNDER_MIFID = listOf(
            Dimension.OBJECTIVE,
            Dimension.RISK_WILLINGNESS,
            Dimension.LOSS_CAPACITY,
            Dimension.FINANCIAL_SITUATION,
            Dimension.KNOWLEDGE,
            Dimension.EXPERIENCE,
        )
    }
}

/** Two languages are the minimum every pack carries; the client picks one, `cs` is the fallback. */
data class LocalizedText(val cs: String, val en: String) {
    init {
        require(cs.isNotBlank() && en.isNotBlank()) { "localized text must carry both cs and en" }
    }

    fun text(language: String?): String = if (language?.lowercase()?.startsWith("en") == true) en else cs
}

data class QuestionStep(
    val code: String,
    val title: LocalizedText,
    /** An optional step may be skipped as a whole (the sustainability step). */
    val optional: Boolean = false,
)

/** What a question measures. Generic — the wording and options are data. */
enum class Dimension {
    /** Why the participant saves: capital preservation .. growth. Risk-bearing. */
    OBJECTIVE,

    /** Behavioural willingness to bear swings (scenario-based, not a self-label). Risk-bearing. */
    RISK_WILLINGNESS,

    /** Ability to bear loss: the share of the savings the participant could lose. Risk-bearing. */
    LOSS_CAPACITY,

    /** Income stability, reserves, obligations. Risk-bearing. */
    FINANCIAL_SITUATION,

    /** Current savings that the loss capacity is expressed against (illustration in money). */
    SAVINGS,

    /** Factual knowledge of one instrument class (DIP / MiFID). */
    KNOWLEDGE,

    /** Transactions actually made in one instrument class (DIP / MiFID). */
    EXPERIENCE,

    /** Sustainability preference (Delegated Reg. 2017/565 Art. 2(7) as amended by 2021/1253). */
    SUSTAINABILITY,
}

/** The three categories of a sustainability preference in Art. 2(7)(a)-(c). */
enum class SustainabilityCategory {
    /** (a) a minimum proportion of environmentally sustainable investments (Taxonomy). */
    TAXONOMY_ALIGNED,

    /** (b) a minimum proportion of sustainable investments (SFDR Art. 2(17)). */
    SFDR_SUSTAINABLE,

    /** (c) consideration of principal adverse impacts. */
    PAI_CONSIDERED,
}

data class Question(
    val id: String,
    val step: String,
    val dimension: Dimension,
    val text: LocalizedText,
    val help: LocalizedText? = null,
    val options: List<AnswerOption>,
    val required: Boolean = true,
    /** For KNOWLEDGE / EXPERIENCE: the instrument class asked about (e.g. `EQUITY_FUNDS`). */
    val instrumentClass: String? = null,
) {
    init {
        require(id.isNotBlank()) { "question id must not be blank" }
        require(options.size >= 2) { "question $id needs at least two options" }
        require(options.map { it.code }.toSet().size == options.size) { "question $id: option codes must be unique" }
        require((dimension in INSTRUMENT_DIMENSIONS) == (instrumentClass != null)) {
            "question $id: instrumentClass is required for, and only for, KNOWLEDGE/EXPERIENCE"
        }
        if (dimension in RISK_BEARING) {
            require(
                options.all {
                    it.maxRiskClass != null
                },
            ) { "risk-bearing question $id: every option needs maxRiskClass" }
        }
        if (dimension == Dimension.LOSS_CAPACITY) {
            require(
                options.all {
                    it.lossSharePercent != null
                },
            ) { "loss-capacity question $id: options need lossSharePercent" }
        }
        if (dimension == Dimension.SAVINGS) {
            require(options.all { it.amountCzk != null }) { "savings question $id: options need amountCzk" }
        }
        if (dimension == Dimension.SUSTAINABILITY) {
            require(options.all { it.sustainability != null }) { "sustainability question $id: options need a mapping" }
        }
        if (dimension in INSTRUMENT_DIMENSIONS) {
            require(options.all { it.score in 0..MAX_SCORE }) { "question $id: scores must be in 0..3" }
        }
    }

    fun option(code: String): AnswerOption? = options.firstOrNull { it.code == code }

    companion object {
        const val MAX_SCORE = 3
        val RISK_BEARING = setOf(
            Dimension.OBJECTIVE,
            Dimension.RISK_WILLINGNESS,
            Dimension.LOSS_CAPACITY,
            Dimension.FINANCIAL_SITUATION,
        )
        val INSTRUMENT_DIMENSIONS = setOf(Dimension.KNOWLEDGE, Dimension.EXPERIENCE)
    }
}

data class AnswerOption(
    val code: String,
    val text: LocalizedText,
    /** 0..3 for knowledge/experience; for risk-bearing options, the 0..3 legacy scale. */
    val score: Int = 0,
    /** Risk-bearing: the highest SRI class (1..7) this answer allows. */
    val maxRiskClass: Int? = null,
    /** Loss capacity: the share of current savings the participant can lose without changing plans. */
    val lossSharePercent: Int? = null,
    /** Savings: the representative amount of the band, for the money illustration. */
    val amountCzk: BigDecimal? = null,
    /** Financial situation: whether this answer means the situation is stable. */
    val stable: Boolean? = null,
    val sustainability: SustainabilityMapping? = null,
) {
    init {
        require(code.isNotBlank()) { "option code must not be blank" }
        require(maxRiskClass == null || maxRiskClass in StrategyOption.MIN_RISK_CLASS..StrategyOption.MAX_RISK_CLASS) {
            "option $code: maxRiskClass must be in 1..7"
        }
        require(lossSharePercent == null || lossSharePercent in 0..PERCENT) { "option $code: lossSharePercent 0..100" }
        require(amountCzk == null || amountCzk.signum() >= 0) { "option $code: amountCzk must be >= 0" }
    }

    private companion object {
        const val PERCENT = 100
    }
}

/**
 * Plain-language sustainability choice mapped to the regulatory categories. An empty [categories]
 * means "no preference" — a valid answer that must be recorded as such, not as a missing one.
 */
data class SustainabilityMapping(
    val categories: Set<SustainabilityCategory> = emptySet(),
    /** Minimum proportion in percent for categories (a)/(b); null for (c) or no preference. */
    val minSharePercent: Int? = null,
) {
    init {
        val needsShare = categories.any { it != SustainabilityCategory.PAI_CONSIDERED }
        require(!needsShare || (minSharePercent != null && minSharePercent in 1..MAX_PERCENT)) {
            "a taxonomy/SFDR preference needs a minimum share in 1..100"
        }
    }

    private companion object {
        const val MAX_PERCENT = 100
    }
}

/**
 * Two answers that cannot both be true (ESMA guideline on consistency): when [question] is one of
 * [options] AND [conflictsWith] is one of [conflictingOptions], the participant is asked to review.
 */
data class ConsistencyRule(
    val code: String,
    val question: String,
    val options: Set<String>,
    val conflictsWith: String,
    val conflictingOptions: Set<String>,
    val message: LocalizedText,
)

data class WarningText(val code: WarningCode, val text: LocalizedText)

/** The loaded question sets; one per `(jurisdiction, productLine)` pack key, latest version wins. */
interface QuestionSetRegistry {
    fun questionSet(jurisdiction: String, productLine: ProductLine): QuestionSet
}
