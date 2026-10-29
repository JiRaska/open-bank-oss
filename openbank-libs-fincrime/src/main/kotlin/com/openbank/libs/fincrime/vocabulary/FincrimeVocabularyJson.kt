// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.vocabulary

import com.openbank.libs.product.ir.IrJson
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Strict, bounded source decoding into the existing vocabulary compiler. No source authentication,
 * registry lookup or activation authority. Callers must also bound transport reads before [compile].
 * The one-MiB wire limit is independent of the typed compiler's per-entry limits.
 */
object FincrimeVocabularyJson {
    const val MAX_SOURCE_BYTES: Int = 1048576

    fun compile(json: String): FincrimeVocabulary {
        require(json.length <= MAX_SOURCE_BYTES) { "vocabulary source exceeds size limit" }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) { "vocabulary source exceeds byte limit" }
        val root = fields(
            IrJson.parse(json),
            setOf(
                "schemaVersion", "id", "revision", "domain", "purpose", "provenance",
                "effectiveFrom", "effectiveUntil", "entries",
            ),
        )
        val entries = root["entries"] as? List<*> ?: throw IllegalArgumentException("entries must be an array")
        require(entries.size in 1..FincrimeVocabulary.MAX_ENTRIES) { "vocabulary requires 1..512 entries" }
        return FincrimeVocabulary.compile(
            VocabularySource(
                integer(root["schemaVersion"]), text(root["id"]), integer(root["revision"]),
                text(root["domain"]), text(root["purpose"]), text(root["provenance"]),
                instant(root["effectiveFrom"]), root["effectiveUntil"]?.let { instant(it) },
                entries.map {
                    val entry = fields(it, setOf("code", "label", "meaning", "rank"))
                    VocabularyEntry(
                        VocabularyCode(text(entry["code"])),
                        text(entry["label"]),
                        text(entry["meaning"]),
                        entry["rank"]?.let { rank -> integer(rank) },
                    )
                },
            ),
        )
    }

    private fun fields(value: Any?, expected: Set<String>): Map<*, *> {
        val result = value as? Map<*, *> ?: throw IllegalArgumentException("expected vocabulary object")
        require(result.keys == expected) { "unknown or missing vocabulary fields" }
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

    private fun instant(value: Any?): Instant = try {
        Instant.parse(text(value))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("invalid vocabulary instant", e)
    }
}
