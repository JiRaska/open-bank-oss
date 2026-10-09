// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.questionnaire

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import java.time.Instant

/** Immutable published catalog evidence for one strategy offering. */
data class StrategyInstrumentMapping(
    val jurisdiction: String,
    val productLine: ProductLine,
    val strategyCode: String,
    val revision: String,
    val instrumentClasses: Set<String>,
) {
    init {
        require(jurisdiction.isNotBlank() && strategyCode.isNotBlank() && revision.isNotBlank())
        require(instrumentClasses.isNotEmpty() && instrumentClasses.all { it.isNotBlank() })
    }
}

/** Catalog lookup returns all effective published offerings; zero or several is ambiguous. */
interface StrategyInstrumentMappingPort {
    suspend fun effectivePublished(
        jurisdiction: String,
        productLine: ProductLine,
        strategyCode: String,
        at: Instant,
    ): List<StrategyInstrumentMapping>
}

/** Evidence bound to a specific assessment and catalog revision at strategy choice. */
data class StrategyInstrumentDecision(
    val assessmentId: java.util.UUID,
    val jurisdiction: String,
    val productLine: ProductLine,
    val strategyCode: String,
    val mappingRevision: String,
    val instrumentClasses: Set<String>,
    val appropriate: Boolean,
)

/** A missing, ambiguous, or changed catalog mapping must never inherit aggregate appropriateness. */
object StrategyInstrumentGate {
    private const val MAX_COMBINED_SCORE = 6

    fun evaluate(
        assessment: SuitabilityAssessment,
        jurisdiction: String,
        strategyCode: String,
        mappings: List<StrategyInstrumentMapping>,
        minimumCombinedScore: Int,
    ): StrategyInstrumentDecision {
        require(minimumCombinedScore in 0..MAX_COMBINED_SCORE)
        check(mappings.size == 1) { "exactly one published instrument mapping is required for $strategyCode" }
        val mapping = mappings.single()
        check(
            mapping.jurisdiction == jurisdiction &&
                mapping.productLine == assessment.productLine &&
                mapping.strategyCode == strategyCode,
        ) { "instrument mapping scope mismatch" }
        val competence = checkNotNull(assessment.questionnaire) {
            "per-class questionnaire evidence is required for strategy choice"
        }.competence
        check(competence.map { it.instrumentClass }.toSet().size == competence.size) {
            "duplicate instrument-class competence in assessment"
        }
        val byClass = competence.associateBy { it.instrumentClass }
        check(mapping.instrumentClasses.all { it in byClass }) {
            "questionnaire did not assess every instrument class in $strategyCode"
        }
        return StrategyInstrumentDecision(
            assessment.id,
            jurisdiction,
            assessment.productLine,
            strategyCode,
            mapping.revision,
            mapping.instrumentClasses,
            mapping.instrumentClasses.all { cls ->
                val score = checkNotNull(byClass[cls])
                score.knowledge + score.experience >= minimumCombinedScore
            },
        )
    }

    fun requireCurrent(pinned: StrategyInstrumentDecision, current: List<StrategyInstrumentMapping>) {
        check(current.size == 1) { "published instrument mapping is missing or ambiguous" }
        val mapping = current.single()
        check(
            mapping.jurisdiction == pinned.jurisdiction &&
                mapping.productLine == pinned.productLine &&
                mapping.strategyCode == pinned.strategyCode &&
                mapping.revision == pinned.mappingRevision &&
                mapping.instrumentClasses == pinned.instrumentClasses,
        ) { "strategy instrument mapping changed after choice" }
    }
}
