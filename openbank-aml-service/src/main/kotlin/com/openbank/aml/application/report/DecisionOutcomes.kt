// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.report

import com.openbank.aml.domain.report.OutcomeLabel
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabulary
import com.openbank.libs.fincrime.vocabulary.VocabularyCode
import com.openbank.libs.fincrime.vocabulary.VocabularyResolution
import java.time.Instant

/**
 * The structured outcome every closing decision records (ADR-0371 §10).
 *
 * The codes are a versioned vocabulary — data — and not a Kotlin enum, so this class knows none of
 * them. A code the pinned revision does not hold at the decision instant is refused; it is never
 * mapped to a default. The label stored with a decision carries the vocabulary id, revision and
 * hash, so a later reader knows exactly which meaning the code had.
 */
class DecisionOutcomes(private val vocabulary: FincrimeVocabulary) {
    val codes: List<String> = vocabulary.entries.map { it.code.value }

    fun label(code: String?, at: Instant): OutcomeLabel {
        require(!code.isNullOrBlank()) { "outcomeCode is required" }
        val parsed = runCatching { VocabularyCode(code) }.getOrElse { throw IllegalArgumentException("unknown outcome code") }
        val reference = vocabulary.reference
        return when (vocabulary.resolve(reference, parsed, at)) {
            is VocabularyResolution.Resolved -> OutcomeLabel(reference.id, reference.revision, reference.contentHash, code)
            else -> throw IllegalArgumentException("unknown outcome code '$code' in ${reference.id} revision ${reference.revision}")
        }
    }
}
