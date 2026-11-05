// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain

import com.openbank.finrep.domain.mapper.FinrepCoverage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every leaf GL account the ledger seeds must be governed by FINREP: classified into template rows,
 * or declared off-balance memo with a reason ([FinrepCoverage]). The chart is DERIVED from the
 * ledger's Flyway migrations rather than listed here, so a migration that adds an account fails
 * this test at PR time instead of blocking a return with `UNCLASSIFIED_GL_ACCOUNT` at report time
 * (#14284: the 66 ADR-0344 instrument accounts of V35 shipped unclassified).
 */
class LedgerChartCoverageTest {
    private val migrations = File("../openbank-ledger-service/src/main/resources/db/migration")

    // ('<id>', '<code>', '<name>', '<TYPE>', '<CCY>', <is_leaf>, <is_enabled>) rows of gl_accounts inserts.
    private val accountRow = Regex(
        """\(\s*(?:gen_random_uuid\(\)|'[^']*'),\s*'(\d{4})',\s*'(?:[^']|'')*',\s*'([A-Z]+)',\s*'[A-Z]{3}',\s*(true|false),\s*(true|false)\s*\)""",
    )

    private fun seededLeafCodes(): Set<String> {
        val files = migrations.listFiles { f -> f.name.endsWith(".sql") }.orEmpty()
        assertThat(files).describedAs("ledger migrations at ${migrations.absolutePath}").isNotEmpty()
        return files
            .flatMap { file -> accountRow.findAll(file.readText()).toList() }
            .filter { it.groupValues[3] == "true" }
            .map { it.groupValues[1] }
            .toSortedSet()
    }

    @Test
    fun `the derived chart is a real chart, not an empty parse`() {
        // Known-positive: codes from V1, V29 and V35 must all be found, or the probe is broken.
        assertThat(seededLeafCodes()).contains("1001", "1500", "1600", "9000").hasSizeGreaterThan(80)
    }

    @Test
    fun `every leaf account seeded by ledger migrations is classified or declared memo`() {
        val ungoverned = seededLeafCodes().filterNot(FinrepCoverage::isGoverned)
        assertThat(ungoverned).describedAs("ledger leaf accounts with no FINREP classification").isEmpty()
    }
}
