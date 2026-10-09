// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.onboarding

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.QuestionnaireAnswers
import com.openbank.pension.domain.onboarding.QuestionnaireRegime
import com.openbank.pension.domain.onboarding.QuestionnaireRules
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.questionnaire.InstrumentCompetence
import com.openbank.pension.domain.questionnaire.QuestionnaireRecord
import com.openbank.pension.domain.questionnaire.StrategyInstrumentGate
import com.openbank.pension.domain.questionnaire.StrategyInstrumentMapping
import com.openbank.pension.domain.questionnaire.SustainabilityPreference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class StrategyInstrumentGateTest {
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val assessment = SuitabilityAssessment.assess(
        UUID.randomUUID(),
        UUID.randomUUID(),
        ProductLine.DIP,
        QuestionnaireRules(
            regime = QuestionnaireRegime.MIFID_SUITABILITY,
            appropriatenessTest = true,
            appropriatenessMinScore = 3,
            esgPreferenceRequired = false,
            allowUnsuitableWithWarning = false,
            validityDays = 365,
        ),
        QuestionnaireAnswers(3, 3, 2, 2, true),
        LocalDate.parse("2026-10-09"),
        now,
    ).copy(
        questionnaire = QuestionnaireRecord(
            "dip-q1", 1, emptyMap(), 5, emptyList(),
            listOf(InstrumentCompetence("BOND_FUNDS", 3, 3), InstrumentCompetence("EQUITY_FUNDS", 0, 0)),
            SustainabilityPreference.NONE, null, null, null, emptyList(),
        ),
    )

    private fun mapping(classes: Set<String> = setOf("BOND_FUNDS", "EQUITY_FUNDS")) =
        StrategyInstrumentMapping("CZ", ProductLine.DIP, "DYNAMIC", "revision-7", classes)

    @Test
    fun `selected strategy evaluates every covered class rather than aggregate best class`() {
        assertThat(assessment.appropriate).isTrue()
        val decision = StrategyInstrumentGate.evaluate(assessment, "CZ", "DYNAMIC", listOf(mapping()), 3)
        assertThat(decision.appropriate).isFalse()
        assertThat(decision.mappingRevision).isEqualTo("revision-7")
        assertThat(decision.instrumentClasses).containsExactlyInAnyOrder("BOND_FUNDS", "EQUITY_FUNDS")
    }

    @Test
    fun `absent ambiguous and unassessed mappings fail closed`() {
        assertThatThrownBy { StrategyInstrumentGate.evaluate(assessment, "CZ", "DYNAMIC", emptyList(), 3) }
            .hasMessageContaining("exactly one")
        assertThatThrownBy {
            StrategyInstrumentGate.evaluate(assessment, "CZ", "DYNAMIC", listOf(mapping(), mapping()), 3)
        }
            .hasMessageContaining("exactly one")
        assertThatThrownBy {
            StrategyInstrumentGate.evaluate(assessment, "CZ", "DYNAMIC", listOf(mapping(setOf("UNASKED_CLASS"))), 3)
        }.hasMessageContaining("did not assess")
        assertThatThrownBy {
            StrategyInstrumentGate.evaluate(
                assessment.copy(questionnaire = null),
                "CZ",
                "DYNAMIC",
                listOf(mapping()),
                3,
            )
        }.hasMessageContaining("per-class questionnaire")
    }

    @Test
    fun `catalog revision or class change invalidates a pinned decision`() {
        val decision = StrategyInstrumentGate.evaluate(assessment, "CZ", "DYNAMIC", listOf(mapping()), 3)
        StrategyInstrumentGate.requireCurrent(decision, listOf(mapping()))
        assertThatThrownBy { StrategyInstrumentGate.requireCurrent(decision, emptyList()) }
            .hasMessageContaining("missing or ambiguous")
        assertThatThrownBy {
            StrategyInstrumentGate.requireCurrent(decision, listOf(mapping().copy(revision = "revision-8")))
        }.hasMessageContaining("changed after choice")
        assertThatThrownBy {
            StrategyInstrumentGate.requireCurrent(decision, listOf(mapping(setOf("BOND_FUNDS"))))
        }.hasMessageContaining("changed after choice")
        assertThatThrownBy {
            StrategyInstrumentGate.requireCurrent(decision, listOf(mapping().copy(jurisdiction = "SK")))
        }.hasMessageContaining("changed after choice")
        assertThatThrownBy {
            StrategyInstrumentGate.evaluate(
                assessment,
                "CZ",
                "DYNAMIC",
                listOf(mapping().copy(productLine = ProductLine.DPS)),
                3,
            )
        }.hasMessageContaining("scope mismatch")
    }
}
