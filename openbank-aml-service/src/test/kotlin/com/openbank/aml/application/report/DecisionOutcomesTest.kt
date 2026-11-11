// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.report

import com.openbank.libs.fincrime.vocabulary.FincrimeVocabularyJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant

class DecisionOutcomesTest {
    private val outcomes = DecisionOutcomes(
        FincrimeVocabularyJson.compile(File("src/main/resources/aml/decision-outcomes.provisional.json").readText()),
    )
    private val at = Instant.parse("2026-06-01T00:00:00Z")

    @Test
    fun `the shipped vocabulary covers confirmed, false positive with a reason, and insufficient information`() {
        assertThat(outcomes.codes).contains("CONFIRMED_SUSPICION", "INSUFFICIENT_INFORMATION")
        assertThat(outcomes.codes.filter { it.startsWith("FALSE_POSITIVE_") }).hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `a label pins the vocabulary revision and hash the code was read from`() {
        val label = outcomes.label("FALSE_POSITIVE_DATA_QUALITY", at)
        assertThat(label.code).isEqualTo("FALSE_POSITIVE_DATA_QUALITY")
        assertThat(label.vocabularyId).isEqualTo("aml.decision-outcomes")
        assertThat(label.vocabularyRevision).isEqualTo(1)
        assertThat(label.vocabularyHash).matches("[0-9a-f]{64}")
    }

    @Test
    fun `an unknown, blank or out-of-validity code is refused, never defaulted`() {
        listOf("FALSE_POSITIVE", "confirmed_suspicion", "", null, "not a code").forEach { code ->
            assertThatThrownBy {
                outcomes.label(code, at)
            }.describedAs("$code").isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { outcomes.label("CONFIRMED_SUSPICION", Instant.parse("2025-12-31T23:59:59Z")) }
            .describedAs("before the vocabulary took effect").isInstanceOf(IllegalArgumentException::class.java)
    }
}
