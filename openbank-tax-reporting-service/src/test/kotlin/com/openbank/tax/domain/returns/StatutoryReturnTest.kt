// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.returns

import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class StatutoryReturnTest {
    private val balanceSheet = ReturnDefinition(
        code = "PSP10-12-FUND",
        name = "balance sheet",
        scope = ReturnScope.FUND,
        periodicity = Periodicity.MONTH,
        deadlineDaysAfterPeriodEnd = 20,
        datapoints = listOf("total_assets", "total_liabilities", "total_equity"),
        rules = listOf(
            ValidationRule.NonNegative("assets-non-negative", listOf("total_assets", "total_liabilities")),
            ValidationRule.SumEquals(
                "balance-sheet-balances",
                "total_assets",
                listOf(SignedTerm("total_liabilities", 1), SignedTerm("total_equity", 1)),
            ),
        ),
        legalBasis = "test",
    )
    private val catalogue = ReturnCatalogue("cz-pension-cnb", 1, "CZ", false, listOf(balanceSheet))
    private val july = ReportingPeriod(Periodicity.MONTH, LocalDate.of(2026, 7, 31))
    private val at = Instant.parse("2026-08-05T09:00:00Z")

    private val goodValues = mapOf(
        "total_assets" to BigDecimal("1500000.50"),
        "total_liabilities" to BigDecimal("100000.5"),
        "total_equity" to BigDecimal("1400000.00"),
    )

    private fun assembled(values: Map<String, BigDecimal> = goodValues) =
        StatutoryReturn.assemble(catalogue, balanceSheet, "fund-conservative", july, 1, values, "maker", at)

    @Test
    fun `golden - canonical content and hash are fixed, order- and scale-independent`() {
        val ret = assembled()
        assertThat(
            StatutoryReturn.canonicalContent(
                ret.catalogueId,
                ret.catalogueVersion,
                ret.returnCode,
                ret.entityId,
                ret.period,
                ret.revision,
                ret.datapoints,
            ),
        ).isEqualTo(
            "catalogue=cz-pension-cnb@1\nreturn=PSP10-12-FUND\nentity=fund-conservative\n" +
                "period=MONTH:2026-07\nrevision=1\n" +
                "total_assets=1500000.5\ntotal_equity=1400000\ntotal_liabilities=100000.5\n",
        )
        assertThat(ret.contentHash).isEqualTo("904936295b99bd9f0b8ec881948c226f2c0e1c782b429f1a51987f90eef08eee")
        assertThat(ret.dueDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(ret.status).isEqualTo(ReturnStatus.ASSEMBLED)
    }

    @Test
    fun `an unbalanced balance sheet is refused with the rule that broke`() {
        assertThatThrownBy { assembled(goodValues + ("total_equity" to BigDecimal("1400001"))) }
            .isInstanceOf(ReturnValidationException::class.java)
            .satisfies({ e ->
                assertThat(
                    (e as ReturnValidationException).findings.map {
                        it.ruleId
                    },
                ).containsExactly("balance-sheet-balances")
            })
    }

    @Test
    fun `a negative figure is refused`() {
        val values = mapOf(
            "total_assets" to BigDecimal("-1"),
            "total_liabilities" to BigDecimal("-1"),
            "total_equity" to BigDecimal.ZERO,
        )
        assertThatThrownBy { assembled(values) }
            .isInstanceOf(ReturnValidationException::class.java)
            .satisfies({ e ->
                assertThat(
                    (e as ReturnValidationException).findings.map {
                        it.ruleId
                    },
                ).containsOnly("assets-non-negative")
            })
    }

    @Test
    fun `a missing datapoint is a finding, never a zero`() {
        assertThatThrownBy { assembled(goodValues - "total_equity") }
            .isInstanceOf(ReturnValidationException::class.java)
            .satisfies({ e ->
                assertThat((e as ReturnValidationException).findings.map { it.ruleId }).containsExactly("REQUIRED")
            })
    }

    @Test
    fun `an undeclared datapoint is a schema finding`() {
        assertThatThrownBy { assembled(goodValues + ("extra" to BigDecimal.ONE)) }
            .isInstanceOf(ReturnValidationException::class.java)
            .satisfies({ e ->
                assertThat((e as ReturnValidationException).findings.map { it.ruleId }).containsExactly("SCHEMA")
            })
    }

    @Test
    fun `a rule over a datapoint the return does not carry rejects the catalogue`() {
        assertThatThrownBy {
            balanceSheet.copy(rules = listOf(ValidationRule.NonNegative("typo", listOf("total_asets"))))
        }.isInstanceOf(TaxValidationException::class.java).hasMessageContaining("total_asets")
    }

    @Test
    fun `the assembler may not approve - four eyes`() {
        assertThatThrownBy { assembled().approve("maker", at) }
            .isInstanceOf(TaxConflictException::class.java).hasMessageContaining("Four-eyes")
    }

    @Test
    fun `approval attests the content hash and submission records the reference`() {
        val approved = assembled().approve("checker", at)
        assertThat(approved.attestedHash).isEqualTo(approved.contentHash)
        val submitted = approved.submit("SDAT-2026-000123", "checker", at)
        assertThat(submitted.status).isEqualTo(ReturnStatus.SUBMITTED)
        assertThat(submitted.submissionReference).isEqualTo("SDAT-2026-000123")
    }

    @Test
    fun `figures changed after attestation cannot be submitted`() {
        val approved = assembled().approve("checker", at)
        val tampered = approved.copy(datapoints = approved.datapoints + ("total_equity" to BigDecimal("1")))
        assertThatThrownBy { tampered.submit("REF", "checker", at) }
            .isInstanceOf(TaxConflictException::class.java).hasMessageContaining("attested hash")
    }

    @Test
    fun `an unapproved return cannot be submitted`() {
        assertThatThrownBy { assembled().submit("REF", "checker", at) }
            .isInstanceOf(TaxConflictException::class.java).hasMessageContaining("not APPROVED")
    }

    @Test
    fun `overdue only after the due date and only until submitted`() {
        val ret = assembled()
        assertThat(ret.isOverdueAt(LocalDate.of(2026, 8, 20))).isFalse()
        assertThat(ret.isOverdueAt(LocalDate.of(2026, 8, 21))).isTrue()
        val submitted = ret.approve("checker", at).submit("REF", "checker", at)
        assertThat(submitted.isOverdueAt(LocalDate.of(2026, 9, 1))).isFalse()
    }

    @Test
    fun `period labels parse and render per periodicity`() {
        assertThat(ReportingPeriod.parse(Periodicity.QUARTER, "2026-Q3").endDate).isEqualTo(LocalDate.of(2026, 9, 30))
        assertThat(ReportingPeriod.parse(Periodicity.YEAR, "2025").label).isEqualTo("2025")
        assertThat(ReportingPeriod.parse(Periodicity.MONTH, "2026-02").endDate).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThatThrownBy {
            ReportingPeriod.parse(Periodicity.MONTH, "2026-Q3")
        }.isInstanceOf(TaxValidationException::class.java)
        assertThatThrownBy {
            ReportingPeriod(Periodicity.QUARTER, LocalDate.of(2026, 8, 31))
        }.isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `lastEndedBefore never returns the running period`() {
        val today = LocalDate.of(2026, 10, 1)
        assertThat(ReportingPeriod.lastEndedBefore(Periodicity.QUARTER, today).label).isEqualTo("2026-Q3")
        assertThat(
            ReportingPeriod.lastEndedBefore(Periodicity.MONTH, LocalDate.of(2026, 9, 30)).label,
        ).isEqualTo("2026-08")
        assertThat(ReportingPeriod.lastEndedBefore(Periodicity.YEAR, today).label).isEqualTo("2025")
    }
}
