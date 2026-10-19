// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.tax.application.port.out.CompanyPortfolio
import com.openbank.tax.application.port.out.CompanyPortfolioPosition
import com.openbank.tax.application.port.out.PortfolioQuantityBasis
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.infrastructure.returns.pension.Psp3412PsAssembler
import com.openbank.tax.infrastructure.returns.pension.Psp3412PsRow
import com.openbank.tax.infrastructure.returns.pension.TreasuryCompanyPortfolio
import com.openbank.tax.infrastructure.returns.pension.TreasuryPortfolioDto
import com.openbank.tax.infrastructure.returns.pension.TreasuryPositionDto
import com.openbank.tax.infrastructure.returns.pension.TreasurySourceStatementDto
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

    private fun pos(
        cls: String,
        isin: String,
        qty: String,
        value: String,
        ccy: String = "CZK",
        quantityBasis: PortfolioQuantityBasis = if (cls == "GOVERNMENT_BOND" || cls == "CORPORATE_BOND") {
            PortfolioQuantityBasis.FACE_AMOUNT
        } else {
            PortfolioQuantityBasis.UNIT
        },
    ) = CompanyPortfolioPosition(cls, isin, BigDecimal(qty), BigDecimal(value), ccy, quantityBasis)

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
            Psp3412PsRow(
                "GOVERNMENT_BOND",
                "CZ0001004600",
                BigDecimal("500"),
                BigDecimal("498765.43"),
                PortfolioQuantityBasis.FACE_AMOUNT,
            ),
            Psp3412PsRow(
                "GOVERNMENT_BOND",
                "CZ0001005037",
                BigDecimal("1000"),
                BigDecimal("1012345.67"),
                PortfolioQuantityBasis.FACE_AMOUNT,
            ),
            Psp3412PsRow(
                "EQUITY",
                "CZ0008019106",
                BigDecimal("2500"),
                BigDecimal("2412500.00"),
                PortfolioQuantityBasis.UNIT,
            ),
            Psp3412PsRow(
                "FUND_UNITS",
                "CZ0008474053",
                BigDecimal("12000.5"),
                BigDecimal("15000625.00"),
                PortfolioQuantityBasis.UNIT,
            ),
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
        refused(
            golden.copy(
                positions = golden.positions + pos(
                    "GOVERNMENT_BOND",
                    "US0378331005",
                    "1",
                    "1",
                    quantityBasis = PortfolioQuantityBasis.UNIT,
                ),
            ),
            "mix quantity bases",
        )
    }

    @Test
    fun `a negative valuation fails the catalogue rule rather than being netted silently`() {
        val short = golden.copy(positions = listOf(pos("EQUITY", "CZ0008019106", "-10", "-100")))
        val d = Psp3412PsAssembler.assemble(short, end, "CZK").datapoints
        assertThat(definition.validate(d).map { it.ruleId }).contains("holdings-non-negative")
    }

    private fun validTreasuryWire() = TreasuryPortfolioDto(
        entity = "company",
        asOf = "2026-12-31",
        currency = "CZK",
        positions = listOf(
            TreasuryPositionDto(
                "GOVERNMENT_BOND",
                "CZ0001005037",
                "1000",
                "1012345.67",
                "CZK",
                "FACE_AMOUNT",
            ),
        ),
        sourceStatements = listOf(
            TreasurySourceStatementDto("SAFEKEEPING-01", "00000000-0000-4000-8000-000000000001", null, 1),
            TreasurySourceStatementDto("SAFEKEEPING-02", "00000000-0000-4000-8000-000000000002", "STMT-2", 2),
        ),
    )

    @Test
    fun `the wire decimal strings decode exactly and source metadata is preserved`() {
        val wire = validTreasuryWire()
        val decoded = TreasuryCompanyPortfolio.decode(wire, "company")
        assertThat(decoded.positions.single().valuation).isEqualTo(BigDecimal("1012345.67"))
        assertThat(decoded.sourceStatements).hasSize(2)
        assertThat(decoded.sourceStatements.first().statementId).isNull()
        assertThat(Psp3412PsAssembler.assemble(decoded, end, "CZK").sourceStatements)
            .containsExactlyElementsOf(decoded.sourceStatements)
    }

    @Test
    fun `a missing or unparsable portfolio field refuses the response`() {
        val wire = validTreasuryWire()
        val sourceStatements = wire.sourceStatements!!
        listOf(
            wire.copy(asOf = null),
            wire.copy(asOf = "31.12.2026"),
            wire.copy(positions = null),
            wire.copy(positions = listOf(wire.positions!!.single().copy(valuation = "1e"))),
            wire.copy(positions = listOf(wire.positions!!.single().copy(quantity = null))),
            wire.copy(positions = listOf(wire.positions!!.single().copy(quantityBasis = null))),
            wire.copy(positions = listOf(wire.positions!!.single().copy(quantityBasis = "UNKNOWN"))),
            wire.copy(entity = null),
            wire.copy(entity = "another-company"),
            wire.copy(sourceStatements = null),
            wire.copy(sourceStatements = emptyList()),
            wire.copy(sourceStatements = listOf(sourceStatements.first().copy(statementUuid = "bad"))),
            wire.copy(sourceStatements = listOf(sourceStatements.first().copy(statementUuid = "1-1-1-1-1"))),
            wire.copy(sourceStatements = listOf(sourceStatements.first().copy(version = 0))),
            wire.copy(sourceStatements = sourceStatements + sourceStatements.first()),
            wire.copy(
                sourceStatements = listOf(
                    sourceStatements.first(),
                    sourceStatements[1].copy(safekeepingAccount = sourceStatements.first().safekeepingAccount),
                ),
            ),
            wire.copy(
                sourceStatements = listOf(
                    sourceStatements.first(),
                    sourceStatements[1].copy(statementUuid = sourceStatements.first().statementUuid),
                ),
            ),
        ).forEach { bad ->
            assertThatThrownBy { TreasuryCompanyPortfolio.decode(bad, "company") }
                .isInstanceOf(ReturnDataUnavailableException::class.java).hasMessageContaining("malformed")
        }
    }
}
