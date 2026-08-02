// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The fiscal-year attestation anchor is frozen FOREVER, and this test is the thing that says so.
 *
 * ADR-0096 moved `canonicalNumber` / `escapeJson` out of [FiscalYearTrialBalance] into file-level
 * helpers shared with [PeriodTrialBalance]. That refactor is only safe if the rendered bytes are
 * unchanged: every already-ATTESTED `ledger_year_close` row carries a SHA-256 of the old rendering,
 * and `YearCloseService.attest` re-verifies it fail-closed. Change the canonical form by a single
 * character and every sealed year reads as tampered with — the failure would look exactly like the
 * fraud the anchor exists to detect, which is the worst possible way to discover a formatting tweak.
 *
 * The expected values below were derived INDEPENDENTLY of the Kotlin implementation (rendered and
 * hashed from the documented format by hand) rather than by running this code and pasting what it
 * produced. A self-generated expectation would pass against any rendering, including a broken one,
 * and would be a golden-file test of nothing.
 *
 * If this test ever fails, do not update the constants. The canonical form is a compatibility
 * contract with data already on disk; a deliberate change needs a migration that re-anchors every
 * existing row, not a new literal here.
 */
class FiscalYearHashStabilityTest {

    private fun line(code: String, type: GlAccountType, debit: String, credit: String) = TrialBalanceLine(
        glAccountId = UUID.nameUUIDFromBytes(code.toByteArray()),
        // Deliberately different from `code`: display attributes are excluded from the canonical
        // form, so neither the name nor the id may influence the hash.
        name = "Whatever $code",
        code = code,
        type = type,
        currency = "CZK",
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
    )

    private val trialBalance = FiscalYearTrialBalance(
        fiscalYear = 2025,
        lines = listOf(
            // Deliberately NOT in canonical order — the renderer must sort by (code, currency).
            line("2100", GlAccountType.LIABILITY, "0", "1000.00"),
            line("1100", GlAccountType.ASSET, "1000.00", "0"),
        ),
    )

    @Test
    fun `the canonical rendering is byte-for-byte what already-attested years were hashed from`() {
        assertThat(trialBalance.canonicalJson()).isEqualTo(EXPECTED_JSON)
    }

    @Test
    fun `the content hash of that rendering is unchanged`() {
        assertThat(trialBalance.contentHash()).isEqualTo(EXPECTED_HASH)
    }

    @Test
    fun `scale and line order do not move the hash, but a changed amount does`() {
        val sameValuesDifferentScale = FiscalYearTrialBalance(
            fiscalYear = 2025,
            lines = listOf(
                line("1100", GlAccountType.ASSET, "1000", "0"),
                line("2100", GlAccountType.LIABILITY, "0", "1000"),
            ),
        )
        val oneHellerDifferent = FiscalYearTrialBalance(
            fiscalYear = 2025,
            lines = listOf(
                line("1100", GlAccountType.ASSET, "1000.01", "0"),
                line("2100", GlAccountType.LIABILITY, "0", "1000.01"),
            ),
        )

        assertThat(sameValuesDifferentScale.contentHash()).isEqualTo(EXPECTED_HASH)
        assertThat(oneHellerDifferent.contentHash()).isNotEqualTo(EXPECTED_HASH)
    }

    companion object {
        private const val EXPECTED_JSON =
            """{"fiscalYear":2025,"totalDebit":"1000","totalCredit":"1000","lines":[""" +
                """{"code":"1100","type":"ASSET","currency":"CZK","debit":"1000","credit":"0"},""" +
                """{"code":"2100","type":"LIABILITY","currency":"CZK","debit":"0","credit":"1000"}]}"""

        private const val EXPECTED_HASH = "f817ff3c192fbb34723f219cd62d288d4b2d21faa2b32bbd6d160dad3282315b"
    }
}
