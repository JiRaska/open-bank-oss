// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.openbank.libs.audit.AuditChain
import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.fincrime.vocabulary.VocabularyRef
import java.time.Instant

/** Reuses the kernel codec; exact Long counts are decimal strings, never IEEE-754 numbers. */
internal object CountPackCanonical {
    fun write(source: CountPackSource): String = CanonicalJson.write(
        mapOf(
            "schemaVersion" to source.schemaVersion, "id" to source.id, "revision" to source.revision,
            "domain" to source.domain.name, "purpose" to source.purpose,
            "effectiveFrom" to source.effectiveFrom.toString(), "effectiveUntil" to source.effectiveUntil.toString(),
            "reasons" to vocabulary(source.reasons), "severities" to source.severities?.let { vocabulary(it) },
            "rules" to source.rules.map { rule ->
                mapOf(
                    "id" to rule.id,
                    "feature" to feature(rule.feature),
                    "operator" to "COUNT_AT_LEAST",
                    "maxAgeSeconds" to rule.maxAgeSeconds.toString(),
                    "minimum" to rule.minimum.toString(),
                    "reason" to rule.reason.value,
                    "effect" to effect(rule.effect),
                )
            },
        ),
    )

    private fun vocabulary(ref: VocabularyRef) = mapOf(
        "id" to ref.id,
        "revision" to ref.revision,
        "hash" to ref.contentHash,
    )

    private fun feature(ref: CountFeatureRef) = mapOf(
        "id" to ref.id,
        "revision" to ref.revision,
        "hash" to ref.contentHash,
    )

    private fun effect(effect: CountEffect): Map<String, String> = when (effect) {
        is CountEffect.AmlTriage -> mapOf("type" to "AML_TRIAGE", "severity" to effect.severity.value)
        is CountEffect.FraudAssessment -> mapOf("type" to "FRAUD_ASSESSMENT", "verdict" to effect.verdict.name)
    }

    fun inputHash(at: Instant, inputs: List<CountObservation>): String = AuditChain.sha256Hex(
        CanonicalJson.write(
            mapOf(
                "at" to at.toString(),
                "counts" to inputs.map {
                    mapOf(
                        "feature" to feature(it.reference),
                        "value" to it.value.toString(),
                        "observedAt" to it.observedAt.toString(),
                        "availableAt" to it.availableAt.toString(),
                    )
                },
            ),
        ),
    )
}
