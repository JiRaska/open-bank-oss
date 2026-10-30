// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabulary
import com.openbank.libs.fincrime.vocabulary.VocabularyCode
import com.openbank.libs.fincrime.vocabulary.VocabularyRef
import com.openbank.libs.product.ir.IrJson
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Bounded JSON source adapter for the existing count compiler, not an authenticated publisher.
 * Reuses the kernel's strict JSON syntax reader (including duplicate-key/depth rejection), never
 * product evaluation semantics. The shipped schema describes structure; compilation additionally
 * checks exact vocabulary bindings, validity, unique rules and consistent feature revisions.
 * Callers must bound transport reads before constructing [json]; no I/O or latest lookup occurs here.
 */
object CountPackJson {
    const val MAX_SOURCE_BYTES: Int = 65536

    fun compile(json: String, reasons: FincrimeVocabulary, severities: FincrimeVocabulary? = null): CompiledCountPack {
        // Bound before encoding/parsing so a large caller string cannot allocate an unbounded copy.
        require(json.length <= MAX_SOURCE_BYTES) { "count source exceeds size limit" }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) { "count source exceeds byte limit" }
        val root = fields(
            IrJson.parse(json),
            setOf(
                "schemaVersion", "id", "revision", "domain", "purpose", "effectiveFrom", "effectiveUntil",
                "reasons", "severities", "rules",
            ),
        )
        val rules = root["rules"] as? List<*> ?: throw IllegalArgumentException("rules must be an array")
        require(rules.size in 1..CompiledCountPack.MAX_RULES) { "count source requires 1..64 rules" }
        val source = CountPackSource(
            integer(root["schemaVersion"]), text(root["id"]), integer(root["revision"]),
            PackDomain.valueOf(text(root["domain"])), text(root["purpose"]),
            instant(root["effectiveFrom"]), instant(root["effectiveUntil"]),
            vocabulary(root["reasons"]), root["severities"]?.let { vocabulary(it) }, rules.map { rule(it) },
        )
        return CompiledCountPack.compile(source, reasons, severities)
    }

    private fun rule(value: Any?): CountRule {
        val r = fields(value, setOf("id", "feature", "operator", "maxAgeSeconds", "minimum", "reason", "effect"))
        require(r["operator"] == "COUNT_AT_LEAST") { "unsupported count operator" }
        val f = fields(r["feature"], setOf("id", "revision", "hash"))
        return CountRule(
            text(r["id"]),
            CountFeatureRef(text(f["id"]), integer(f["revision"]), text(f["hash"])),
            count(r["maxAgeSeconds"]),
            count(r["minimum"]),
            VocabularyCode(text(r["reason"])),
            effect(r["effect"]),
        )
    }

    private fun effect(value: Any?): CountEffect {
        val objectValue = value as? Map<*, *> ?: throw IllegalArgumentException("effect must be an object")
        return when (objectValue["type"]) {
            "AML_TRIAGE" -> {
                val e = fields(value, setOf("type", "severity"))
                CountEffect.AmlTriage(VocabularyCode(text(e["severity"])))
            }
            "FRAUD_ASSESSMENT" -> {
                val e = fields(value, setOf("type", "verdict"))
                CountEffect.FraudAssessment(FraudVerdict.valueOf(text(e["verdict"])))
            }
            else -> throw IllegalArgumentException("unsupported count effect")
        }
    }

    private fun vocabulary(value: Any?): VocabularyRef {
        val v = fields(value, setOf("id", "revision", "hash"))
        return VocabularyRef(text(v["id"]), integer(v["revision"]), text(v["hash"]))
    }

    private fun fields(value: Any?, expected: Set<String>): Map<*, *> {
        val result = value as? Map<*, *> ?: throw IllegalArgumentException("expected source object")
        require(result.keys == expected) { "unknown or missing source fields" }
        return result
    }

    private fun text(value: Any?): String = value as? String ?: throw IllegalArgumentException("expected string")

    private fun integer(value: Any?): Int {
        require(value is BigDecimal) { "expected integer number" }
        return try {
            value.intValueExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("integer is fractional or out of range", e)
        }
    }

    private fun count(value: Any?): Long {
        val decimal = text(value)
        require(decimal.matches(Regex("0|[1-9][0-9]{0,18}"))) { "expected unsigned decimal count string" }
        return decimal.toLongOrNull() ?: throw IllegalArgumentException("count exceeds Long range")
    }

    private fun instant(value: Any?): Instant = try {
        Instant.parse(text(value))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("invalid source instant", e)
    }
}
