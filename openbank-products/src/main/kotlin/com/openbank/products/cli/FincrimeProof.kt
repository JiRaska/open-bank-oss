// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products.cli

import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.fincrime.pack.CompiledCountPack
import com.openbank.libs.fincrime.pack.CountEffect
import com.openbank.libs.fincrime.pack.CountEvaluation
import com.openbank.libs.fincrime.pack.CountEvaluationFailure
import com.openbank.libs.fincrime.pack.CountFeatureRef
import com.openbank.libs.fincrime.pack.CountObservation
import com.openbank.libs.fincrime.pack.CountPackJson
import com.openbank.libs.fincrime.pack.CountPackReplay
import com.openbank.libs.fincrime.pack.CountReplayCase
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabularyJson
import com.openbank.libs.product.ir.IrJson
import java.time.Instant

/** Runs the existing synthetic kernel vectors through the canonical source adapters and replay. */
internal object FincrimeProof {
    private const val BOUNDARY_COUNT = 10L
    private const val SHA256_HEX_LENGTH = 64
    private val at = Instant.parse("2026-01-01T00:01:40Z")
    private val feature = CountFeatureRef("velocity_txn_count_h1", 1, "a".repeat(SHA256_HEX_LENGTH))

    fun report(): Map<String, Any?> {
        val aml = proof("aml-count-source.json", "aml-reasons-source.json", "aml-severity-source.json")
        val fraud = proof("fraud-count-pack.canonical.json", "fraud-reasons-source.json", null)
        check(fraud["baselineHash"] == fixture("fraud-count-pack.sha256").trim()) { "fraud golden hash drift" }
        return mapOf("ok" to true, "synthetic" to true, "domains" to listOf(aml, fraud))
    }

    private fun proof(packFile: String, reasonsFile: String, severityFile: String?): Map<String, Any?> {
        val reasons = FincrimeVocabularyJson.compile(fixture(reasonsFile))
        val severities = severityFile?.let { FincrimeVocabularyJson.compile(fixture(it)) }
        val baseline = CountPackJson.compile(fixture(packFile), reasons, severities)
        val candidate = CountPackJson.compile(raisedThreshold(baseline), reasons, severities)
        check(CountPackJson.compile(baseline.canonicalJson, reasons, severities).reference == baseline.reference)
        val observation = CountObservation(feature, BOUNDARY_COUNT, at.minusSeconds(1), at)
        val (boundary, missing) = CountPackReplay.compare(
            baseline,
            candidate,
            listOf(CountReplayCase("boundary", at, listOf(observation)), CountReplayCase("missing", at, emptyList())),
        )
        val before = boundary.baseline as CountEvaluation.Evaluated
        val after = boundary.candidate as CountEvaluation.Evaluated
        val finding = before.findings.single()
        check(boundary.behaviorChanged && after.findings.isEmpty() && before.inputHash == after.inputHash)
        check(before.evaluatedRules == 1 && after.evaluatedRules == 1)
        check(candidate.reference.contentHash != baseline.reference.contentHash)
        val failure = CountEvaluation.Incomplete(CountEvaluationFailure.MISSING_FEATURE, feature.id)
        check(missing.baseline == failure && missing.candidate == failure && !missing.behaviorChanged)
        return mapOf(
            "domain" to baseline.domain.name,
            "baselineHash" to baseline.reference.contentHash,
            "candidateHash" to candidate.reference.contentHash,
            "inputHash" to before.inputHash,
            "evaluatedRules" to before.evaluatedRules + after.evaluatedRules,
            "matchedReason" to finding.reason.value,
            "matchedEffect" to when (val effect = finding.effect) {
                is CountEffect.AmlTriage -> effect.severity.value
                is CountEffect.FraudAssessment -> effect.verdict.name
            },
            "behaviorChanged" to boundary.behaviorChanged,
            "missingInput" to failure.failure.name,
        )
    }

    private fun raisedThreshold(baseline: CompiledCountPack): String {
        val source = IrJson.parse(baseline.canonicalJson) as Map<*, *>
        val rule = (source["rules"] as List<*>).single() as Map<*, *>
        check(rule["minimum"] == BOUNDARY_COUNT.toString()) { "synthetic boundary changed" }
        return CanonicalJson.write(
            source + mapOf(
                "revision" to baseline.reference.revision + 1,
                "rules" to listOf(rule + ("minimum" to (BOUNDARY_COUNT + 1).toString())),
            ),
        )
    }

    private fun fixture(name: String): String {
        val resource = checkNotNull(javaClass.getResourceAsStream("/fincrime-proof/$name"))
        val bytes = resource.use { it.readNBytes(CountPackJson.MAX_SOURCE_BYTES + 1) }
        check(bytes.size <= CountPackJson.MAX_SOURCE_BYTES) { "synthetic fixture exceeds the source limit" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }
}
