// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.openbank.libs.audit.AuditChain
import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabulary
import com.openbank.libs.fincrime.vocabulary.VocabularyResolution
import java.time.Duration
import java.time.Instant
import java.util.Collections

/**
 * Synthetic K0 interpreter (ADR-0354): bounded flat count rules and exact vocabulary/feature pins.
 * No clock, remote lookup, model call, logging, approval, signature or activation. Callers supply
 * authentic snapshots in a future service adapter; input hashes establish identity, not trust.
 */
class CompiledCountPack private constructor(
    private val source: CountPackSource,
    private val reasons: FincrimeVocabulary,
    private val severities: FincrimeVocabulary?,
) {
    val domain: PackDomain = source.domain
    val purpose: String = source.purpose
    val canonicalJson: String = CountPackCanonical.write(source)
    val reference: PackRef = PackRef(source.id, source.revision, AuditChain.sha256Hex(canonicalJson))

    /** All declared inputs must be usable even when a different rule already matches. */
    fun evaluate(expected: PackRef, at: Instant, observations: List<CountObservation>): CountEvaluation {
        if (expected != reference) return incomplete(CountEvaluationFailure.REVISION_MISMATCH)
        if (at < source.effectiveFrom || at >= source.effectiveUntil) {
            return incomplete(CountEvaluationFailure.OUTSIDE_VALIDITY)
        }
        if (!vocabulariesAvailable(at)) return incomplete(CountEvaluationFailure.VOCABULARY_UNAVAILABLE)
        val inputs = observations.take(MAX_RULES + 1).sortedBy { it.reference.id }
        val invalidValues = inputs.any { it.value < 0 || it.observedAt > it.availableAt }
        if (inputs.size > MAX_RULES ||
            inputs.map { it.reference.id }.distinct().size != inputs.size ||
            invalidValues
        ) {
            return incomplete(CountEvaluationFailure.INVALID_INPUT)
        }
        val byId = inputs.associateBy { it.reference.id }
        for (rule in source.rules) {
            val input =
                byId[rule.feature.id] ?: return incomplete(CountEvaluationFailure.MISSING_FEATURE, rule.feature.id)
            inputFailure(rule, input, at)?.let { return incomplete(it, rule.feature.id) }
        }
        val findings = source.rules.filter { byId.getValue(it.feature.id).value >= it.minimum }
            .map { CountFinding(it.id, it.reason, it.effect, source.reasons, source.severities) }
        return CountEvaluation.Evaluated(
            reference,
            at,
            CountPackCanonical.inputHash(at, inputs),
            source.rules.size,
            Collections.unmodifiableList(findings),
        )
    }

    private fun vocabulariesAvailable(at: Instant): Boolean = source.rules.all { rule ->
        reasons.resolve(source.reasons, rule.reason, at) is VocabularyResolution.Resolved &&
            when (val effect = rule.effect) {
                is CountEffect.AmlTriage -> {
                    val resolved = severities?.resolve(checkNotNull(source.severities), effect.severity, at)
                    resolved is VocabularyResolution.Resolved
                }
                is CountEffect.FraudAssessment -> true
            }
    }

    private fun inputFailure(rule: CountRule, input: CountObservation, at: Instant): CountEvaluationFailure? = when {
        input.reference != rule.feature -> CountEvaluationFailure.FEATURE_REVISION_MISMATCH
        input.availableAt > at -> CountEvaluationFailure.FEATURE_UNAVAILABLE
        Duration.between(input.observedAt, at) > Duration.ofSeconds(rule.maxAgeSeconds) ->
            CountEvaluationFailure.STALE_FEATURE
        else -> null
    }

    private fun incomplete(failure: CountEvaluationFailure, id: String? = null) =
        CountEvaluation.Incomplete(failure, id)

    companion object {
        const val MAX_RULES: Int = 64
        const val MAX_AGE_SECONDS: Long = 86400

        fun compile(
            source: CountPackSource,
            reasons: FincrimeVocabulary,
            severities: FincrimeVocabulary? = null,
        ): CompiledCountPack {
            require(source.schemaVersion == 1) { "unsupported count pack schema" }
            packIdentifier(source.id)
            packIdentifier(source.purpose)
            require(source.revision > 0 && source.effectiveUntil > source.effectiveFrom)
            require(reasons.domain == source.domain.name.lowercase(java.util.Locale.ROOT)) { "reason domain mismatch" }
            require(
                severities == null ||
                    (severities.domain == reasons.domain && severities.entries.all { it.rank != null }),
            ) {
                "AML severity must be an ordered scale in the same domain"
            }
            require(source.reasons == reasons.reference) { "reason revision mismatch" }
            require(source.severities == severities?.reference) { "severity revision mismatch" }
            val rules = source.rules.take(MAX_RULES + 1).sortedBy { it.id }
            require(rules.size in 1..MAX_RULES && rules.map { it.id }.distinct().size == rules.size)
            rules.forEach { validateRule(it, source, reasons, severities) }
            require(
                rules.groupBy {
                    it.feature.id
                }.values.all { group -> group.map { it.feature }.distinct().size == 1 },
            ) {
                "one feature identity cannot bind multiple revisions"
            }
            return CompiledCountPack(source.copy(rules = Collections.unmodifiableList(rules)), reasons, severities)
        }

        private fun validateRule(
            rule: CountRule,
            source: CountPackSource,
            reasons: FincrimeVocabulary,
            severities: FincrimeVocabulary?,
        ) {
            packIdentifier(rule.id)
            require(rule.minimum >= 0 && rule.maxAgeSeconds in 1..MAX_AGE_SECONDS)
            require(reasons.resolve(source.reasons, rule.reason, source.effectiveFrom) is VocabularyResolution.Resolved)
            when (val effect = rule.effect) {
                is CountEffect.AmlTriage -> {
                    require(source.domain == PackDomain.AML && source.severities != null)
                    require(
                        severities?.resolve(source.severities, effect.severity, source.effectiveFrom)
                            is VocabularyResolution.Resolved,
                    )
                }
                is CountEffect.FraudAssessment -> {
                    require(source.domain == PackDomain.FRAUD && source.severities == null)
                    require(effect.verdict != FraudVerdict.UNKNOWN) { "UNKNOWN is not an executable verdict" }
                }
            }
        }
    }
}
