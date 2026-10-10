// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.tax.application.port.out.CompanyPortfolio
import com.openbank.tax.application.port.out.CompanyPortfolioPosition
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.infrastructure.returns.pension.Psp3412PsAssembler
import com.openbank.tax.infrastructure.returns.pension.Psp3412PsRow
import com.openbank.tax.infrastructure.returns.pension.TreasuryCompanyPortfolio
import com.openbank.tax.infrastructure.returns.pension.TreasuryPortfolioDto
import com.openbank.tax.infrastructure.returns.pension.TreasuryPositionDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Golden: PSP 34-12 PS assembled from a fixed portfolio of the pension company's own holdings. */
class Psp3412PsAssemblerTest {
    private val end = LocalDate.parse("2026-12-31")
    private val catalogue = CatalogueParser.parse(
        jacksonObjectMapper().findAndRegisterModules(),
        "statutory-returns/cz/pension-cnb.v1.json",
    )
    private val definition = catalogue.definition("PSP34-12-PS")!!

    private fun pos(cls: String, isin: String, qty: String, value: String, ccy: String = "CZK") =
        CompanyPortfolioPosition(cls, isin, BigDecimal(qty), BigDecimal(value), ccy)

    private val golden = CompanyPortfolio(
        asOf = end,
        currency = "CZK",
        positions = listOf(
            pos("EQUITY", "CZ0008019106", "2500", "2412500.00"),
            pos("GOVERNMENT_BOND", "CZ0001005037", "1000", "1012345.67"),
            pos("GOVERNMENT_BOND", "CZ0001004600", "500", "498765.43"),
            pos("FUND_UNITS", "CZ0008474053", "12000.5", "15000625.00"),
        ),
    )

    @Test
    fun `the golden portfolio assembles into class and ISIN rows and valid datapoints`() {
        val report = Psp3412PsAssembler.assemble(golden, end, "CZK")

        assertThat(report.rows).containsExactly(
            Psp3412PsRow("GOVERNMENT_BOND", "CZ0001004600", BigDecimal("500"), BigDecimal("498765.43")),
            Psp3412PsRow("GOVERNMENT_BOND", "CZ0001005037", BigDecimal("1000"), BigDecimal("1012345.67")),
            Psp3412PsRow("EQUITY", "CZ0008019106", BigDecimal("2500"), BigDecimal("2412500.00")),
            Psp3412PsRow("FUND_UNITS", "CZ0008474053", BigDecimal("12000.5"), BigDecimal("15000625.00")),
        )
        val d = report.datapoints
        assertThat(d.getValue("holdings_carrying_value")).isEqualByComparingTo("18924236.10")
        assertThat(d.getValue("holdings_count")).isEqualByComparingTo("4")
        assertThat(d.getValue("government_bond_quantity")).isEqualByComparingTo("1500")
        assertThat(d.getValue("government_bond_valuation")).isEqualByComparingTo("1511111.10")
        assertThat(d.getValue("equity_valuation")).isEqualByComparingTo("2412500.00")
        assertThat(d.getValue("fund_units_quantity")).isEqualByComparingTo("12000.5")
        assertThat(d.getValue("corporate_bond_valuation")).isEqualByComparingTo("0")
        assertThat(d.keys).containsExactlyInAnyOrderElementsOf(definition.datapoints)
        assertThat(definition.validate(d)).isEmpty()
    }

    @Test
    fun `the catalogue datapoints are exactly the assembler's`() {
        assertThat(definition.datapoints).containsExactlyElementsOf(Psp3412PsAssembler.datapointIds())
    }

    @Test
    fun `an empty portfolio is a real zero, not a refusal`() {
        val d = Psp3412PsAssembler.assemble(CompanyPortfolio(end, "CZK", emptyList()), end, "CZK").datapoints
        assertThat(d.getValue("holdings_count")).isEqualByComparingTo("0")
        assertThat(definition.validate(d)).isEmpty()
    }

    @Test
    fun `anything the return cannot faithfully state refuses the whole return`() {
        fun refused(p: CompanyPortfolio, fragment: String) =
            assertThatThrownBy { Psp3412PsAssembler.assemble(p, end, "CZK") }
                .isInstanceOf(ReturnDataUnavailableException::class.java).hasMessageContaining(fragment)
        refused(golden.copy(asOf = end.minusDays(1)), "as of 2026-12-30")
        refused(golden.copy(currency = "EUR"), "is in EUR")
        refused(golden.copy(positions = golden.positions + pos("CRYPTO", "XS0000000009", "1", "1")), "CRYPTO")
        refused(golden.copy(positions = listOf(pos("EQUITY", "not-an-isin", "1", "1"))), "not an ISIN")
        refused(golden.copy(positions = listOf(pos("EQUITY", "US0378331005", "1", "1", "USD"))), "valued in USD")
        refused(golden.copy(positions = golden.positions + golden.positions.first()), "more than once")
    }

    @Test
    fun `a negative valuation fails the catalogue rule rather than being netted silently`() {
        val short = golden.copy(positions = listOf(pos("EQUITY", "CZ0008019106", "-10", "-100")))
        val d = Psp3412PsAssembler.assemble(short, end, "CZK").datapoints
        assertThat(definition.validate(d).map { it.ruleId }).contains("holdings-non-negative")
    }

    @Test
    fun `the wire decimal strings decode exactly, and a missing or unparsable field refuses`() {
        val wire = TreasuryPortfolioDto(
            "2026-12-31",
            "CZK",
            listOf(TreasuryPositionDto("GOVERNMENT_BOND", "CZ0001005037", "1000", "1012345.67", "CZK")),
        )
        val decoded = TreasuryCompanyPortfolio.decode(wire)
        assertThat(decoded.positions.single().valuation).isEqualTo(BigDecimal("1012345.67"))
        listOf(
            wire.copy(asOf = null),
            wire.copy(asOf = "31.12.2026"),
            wire.copy(positions = null),
            wire.copy(positions = listOf(wire.positions!!.single().copy(valuation = "1e"))),
            wire.copy(positions = listOf(wire.positions!!.single().copy(quantity = null))),
        ).forEach { bad ->
            assertThatThrownBy { TreasuryCompanyPortfolio.decode(bad) }
                .isInstanceOf(ReturnDataUnavailableException::class.java).hasMessageContaining("malformed")
        }
    }
}
