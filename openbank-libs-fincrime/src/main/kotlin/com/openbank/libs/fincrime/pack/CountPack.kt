// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.vocabulary.VocabularyCode
import com.openbank.libs.fincrime.vocabulary.VocabularyRef
import java.time.Instant

/** Executable domain boundaries, not a configurable risk taxonomy. */
enum class PackDomain { AML, FRAUD }

/** Content identity only: neither this reference nor compilation authenticates an artifact. */
data class PackRef(val id: String, val revision: Int, val contentHash: String) {
    init {
        packIdentifier(id)
        require(revision > 0)
        packHash(contentHash)
    }
}

/** Pin a COUNT contract, including its producer/window semantics, without creating a feature store. */
data class CountFeatureRef(val id: String, val revision: Int, val contentHash: String) {
    init {
        packIdentifier(id)
        require(revision > 0)
        packHash(contentHash)
    }
}

/** Values are exact counts, never floating-point money or a fabricated zero for missing data. */
data class CountObservation(
    val reference: CountFeatureRef,
    val value: Long,
    val observedAt: Instant,
    val availableAt: Instant,
)

/** AML triage and executable fraud verdicts cannot be converted into each other. */
sealed interface CountEffect {
    data class AmlTriage(val severity: VocabularyCode) : CountEffect
    data class FraudAssessment(val verdict: FraudVerdict) : CountEffect
}

/** Flat COUNT >= minimum instruction: no recursion, script, implicit window aggregation or I/O. */
data class CountRule(
    val id: String,
    val feature: CountFeatureRef,
    val maxAgeSeconds: Long,
    val minimum: Long,
    val reason: VocabularyCode,
    val effect: CountEffect,
)

/** Authoring data. Only a validated immutable compiled snapshot can evaluate it. */
data class CountPackSource(
    val schemaVersion: Int,
    val id: String,
    val revision: Int,
    val domain: PackDomain,
    val purpose: String,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant,
    val reasons: VocabularyRef,
    val severities: VocabularyRef?,
    val rules: List<CountRule>,
)

/** A diagnostic rule finding, not authority to close a case, activate policy or move money. */
data class CountFinding(
    val ruleId: String,
    val reason: VocabularyCode,
    val effect: CountEffect,
    val reasonVocabulary: VocabularyRef,
    val severityVocabulary: VocabularyRef?,
)

enum class CountEvaluationFailure {
    REVISION_MISMATCH,
    OUTSIDE_VALIDITY,
    VOCABULARY_UNAVAILABLE,
    INVALID_INPUT,
    MISSING_FEATURE,
    FEATURE_REVISION_MISMATCH,
    FEATURE_UNAVAILABLE,
    STALE_FEATURE,
}

sealed interface CountEvaluation {
    /** No clean/ALLOW inference is permitted from this state. */
    data class Incomplete(val failure: CountEvaluationFailure, val featureId: String? = null) : CountEvaluation

    /** Empty findings means only that these rules did not match; it is not a fraud ALLOW verdict. */
    data class Evaluated(
        val pack: PackRef,
        val at: Instant,
        val inputHash: String,
        val evaluatedRules: Int,
        val findings: List<CountFinding>,
    ) : CountEvaluation
}

internal fun packIdentifier(value: String) {
    require(value.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) { "invalid identifier" }
}

internal fun packHash(value: String) {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "invalid SHA-256" }
}
