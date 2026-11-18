// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.treasury.domain.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Spec conformance of this module's identifier validators (#16748).
 *
 * The vectors in `openbank-libs-domain/src/test/resources/identifier-spec-vectors/` are taken
 * from each identifier's specification and re-checked against an independent reference
 * (python-stdnum / pycountry) by `.github/scripts/identifier-differential.py`. Every vector file
 * names the modules that implement it; this test runs the ones naming [MODULE]. A case marked
 * `known_defect` asserts today's WRONG verdict, so fixing the defect fails this test until the
 * marker is removed: a known defect can neither be forgotten nor silently fixed.
 *
 * With `IDENTIFIER_ORACLE` set (the `identifier-differential` workflow), it also compares every
 * generated oracle row for these identifiers; any disagreement fails.
 */
class IdentifierSpecConformanceTest {

    private val validators: Map<String, (String) -> Boolean> = mapOf(
        "isin" to Isin::isValid,
    )

    private val vectorDir = File("../openbank-libs-domain/src/test/resources/identifier-spec-vectors")

    private fun owned(): List<JsonNode> = requireNotNull(vectorDir.listFiles()) { "no vector dir at $vectorDir" }
        .filter { it.name.endsWith(".json") }
        .map { ObjectMapper().readTree(it) }
        .filter { v -> v["implementations"].any { it["module"].asText() == MODULE } }

    @Test
    fun `every vector file naming this module has a validator here, and vice versa`() {
        assertEquals(validators.keys, owned().map { it["identifier"].asText() }.toSet())
    }

    @TestFactory
    fun `every spec vector gets the specified verdict`(): List<DynamicTest> = owned().flatMap { v ->
        val id = v["identifier"].asText()
        val validate = validators.getValue(id)
        v["cases"].map { c ->
            val value = c["value"].asText()
            val defect = c["known_defect"]
            val expected = defect?.get("current")?.asBoolean() ?: c["valid"].asBoolean()
            val label = if (defect != null) " [known defect ${defect["issue"].asText()}]" else ""
            DynamicTest.dynamicTest("$id: ${c["rule"].asText()} [$value]$label") {
                assertEquals(expected, validate(value), "$id '$value' — ${c["rule"].asText()}$label")
            }
        }
    }

    @Test
    fun `validators agree with the stdnum oracle`() {
        val path = System.getenv("IDENTIFIER_ORACLE") ?: return
        val rows = File(path).readLines().map { it.split('\t') }.filter { it.size >= 3 && it[0] in validators }
        assertTrue(rows.size > MIN_ROWS * validators.size, "only ${rows.size} oracle rows for ${validators.keys}")
        val disagree = rows.filter { validators.getValue(it[0])(it[1]) != it[2].toBoolean() }
        val (tolerated, bad) = disagree.partition { it.getOrElse(REASON) { "" }.startsWith("known-defect") }
        val markedIds = rows.filter { it.getOrElse(REASON) { "" }.startsWith("known-defect") }.map { it[0] }.toSet()
        markedIds.forEach { id ->
            assertTrue(
                tolerated.any { it[0] == id },
                "$id: no known-defect row disagrees any more: the defect looks fixed, remove its KNOWN_DEFECTS marker",
            )
        }
        assertTrue(
            bad.isEmpty(),
            "${bad.size} of ${rows.size} rows disagree; first 40:\n" +
                bad.take(SHOWN).joinToString("\n") {
                    "${it[0]} '${it[1]}' oracle=${it[2]} (${it.getOrElse(REASON) { "" }})"
                },
        )
        println(
            "IDENTIFIER_DIFFERENTIAL module=$MODULE rows=${rows.size} disagreements=0 known_defect_rows=${tolerated.size}",
        )
    }

    private companion object {
        const val MODULE = "openbank-treasury-service"
        const val MIN_ROWS = 1000
        const val SHOWN = 40
        const val REASON = 3
    }
}
