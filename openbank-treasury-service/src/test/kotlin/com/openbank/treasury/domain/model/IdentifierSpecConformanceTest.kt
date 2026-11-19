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
        val problems = judgeDifferential(rows) { id, value -> validators.getValue(id)(value) }
        assertTrue(
            problems.isEmpty(),
            "${problems.size} problem(s); first $SHOWN:\n" + problems.take(SHOWN).joinToString("\n"),
        )
        val tagged = rows.count { it.getOrElse(REASON) { "" }.startsWith("known-defect") }
        println("IDENTIFIER_DIFFERENTIAL module=$MODULE rows=${rows.size} disagreements=0 known_defect_rows=$tagged")
    }

    @Test
    fun `the differential judge fails a regression and a fixed defect, and tolerates only a live one`() {
        val rows = listOf(
            listOf("x", "a", "true", "stdnum"),
            listOf("x", "b", "false", "known-defect d1 #1"),
            listOf("x", "c", "false", "known-defect d1 #1"),
        )
        fun judge(accepts: Set<String>) = judgeDifferential(rows) { _, v -> v in accepts }
        assertTrue(judge(setOf("a", "b")).isEmpty(), "a live defect (accepts b) must be tolerated")
        assertTrue(judge(setOf("a")).any { "fixed" in it }, "every tagged row agreeing means the defect is fixed")
        assertTrue(judge(setOf("b")).any { "'a'" in it }, "a regression rejecting a valid value must fail")
        assertTrue(judge(setOf("a", "b", "c")).isEmpty(), "still-live defect on all tagged rows is tolerated")
    }

    private companion object {
        const val MODULE = "openbank-treasury-service"
        const val MIN_ROWS = 1000
        const val SHOWN = 40
        const val REASON = 3

        /** Disagreements are tolerated ONLY on rows tagged with a defect, and a defect whose tagged rows all agree must go. */
        fun judgeDifferential(rows: List<List<String>>, ours: (String, String) -> Boolean): List<String> {
            val problems = mutableListOf<String>()
            val liveByDefect = mutableMapOf<String, Int>()
            val taggedByDefect = mutableMapOf<String, Int>()
            for (r in rows) {
                val reason = r.getOrElse(REASON) { "" }
                val defect = if (reason.startsWith("known-defect ")) reason.split(' ')[1] else null
                if (defect != null) taggedByDefect.merge(defect, 1, Int::plus)
                if (ours(r[0], r[1]) == r[2].toBoolean()) continue
                if (defect != null && !r[2].toBoolean()) {
                    liveByDefect.merge(defect, 1, Int::plus)
                } else {
                    problems += "${r[0]} '${r[1]}' oracle=${r[2]} ($reason)"
                }
            }
            taggedByDefect.keys.filter { it !in liveByDefect }.forEach {
                problems +=
                    "$it: all ${taggedByDefect.getValue(
                        it,
                    )} tagged rows agree: the defect is fixed, remove it from KNOWN_DEFECTS"
            }
            return problems
        }
    }
}
