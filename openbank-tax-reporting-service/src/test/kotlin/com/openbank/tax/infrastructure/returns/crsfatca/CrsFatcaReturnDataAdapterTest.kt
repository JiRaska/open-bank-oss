// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.crsfatca

import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.application.usecase.IncomeCreditService
import com.openbank.tax.domain.returns.IncomeCreditTotals
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.infrastructure.returns.CatalogueParser
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The CRS/FATCA mapping, offline: [FixedSources] stands in for the three REST read models and a
 * mocked [IncomeCreditService] for the observed income events. What is pinned here:
 *  - one line per account per reportable residency country (CRS: non-CZ; FATCA: US only);
 *  - a party without a profile, and a party that is not reportable, emit nothing;
 *  - totals count each DISTINCT ACCOUNT once, however many residency lines it emits;
 *  - a wrong catalogue refuses rather than guessing.
 */
class CrsFatcaReturnDataAdapterTest {

    private val catalogue = CatalogueParser.parse(
        jackson(),
        "statutory-returns/cz/tax-information-exchange.v1.json",
    )
    private val period = ReportingPeriod(Periodicity.YEAR, LocalDate.of(2026, 12, 31))

    private val crsParty = UUID.fromString("62000000-0000-0000-0000-000000000062")
    private val fatcaParty = UUID.fromString("72000000-0000-0000-0000-000000000072")
    private val domesticParty = UUID.fromString("82000000-0000-0000-0000-000000000082")
    private val noProfileParty = UUID.fromString("92000000-0000-0000-0000-000000000092")

    private val crsAccount =
        account(UUID.fromString("0a000000-0000-0000-0000-000000000001"), crsParty, "CZ5508000000000000000001")
    private val fatcaAccount =
        account(UUID.fromString("0a000000-0000-0000-0000-000000000002"), fatcaParty, "CZ5508000000000000000002")
    private val domesticAccount =
        account(UUID.fromString("0a000000-0000-0000-0000-000000000003"), domesticParty, "CZ5508000000000000000003")
    private val noProfileAccount =
        account(UUID.fromString("0a000000-0000-0000-0000-000000000004"), noProfileParty, "CZ5508000000000000000004")

    private fun account(id: UUID, partyId: UUID, number: String) = AccountSummaryDto(
        id = id,
        accountNumber = number,
        partyId = partyId,
        productId = UUID.fromString("99999999-8888-4777-8666-555555555555"),
        currencyCode = "CZK",
        status = "ACTIVE",
        openedAt = Instant.parse("2026-01-01T00:00:00Z"),
        closedAt = null,
    )

    private fun facts(
        partyId: UUID,
        holder: String,
        crsStatus: String,
        fatcaStatus: String,
        vararg residencies: Pair<String, String?>,
    ) = PartyTaxFactsDto(
        partyId = partyId,
        holderName = holder,
        profileVersion = 1,
        declaredAt = Instant.parse("2026-06-15T10:00:00Z"),
        crsStatus = crsStatus,
        fatcaStatus = fatcaStatus,
        taxResidencies = residencies.map { TaxResidencyDto(it.first, it.second) },
    )

    private val factsByParty = mapOf(
        crsParty to facts(crsParty, "Crs Resident", "REPORTABLE", "NON_US", "CZ" to null, "DE" to "123456789"),
        fatcaParty to facts(
            fatcaParty,
            "Us Person",
            "NON_REPORTABLE",
            "US_PERSON",
            "CZ" to null,
            "US" to "987-65-4321",
        ),
        domesticParty to facts(domesticParty, "Domestic Only", "NON_REPORTABLE", "NON_US", "CZ" to null),
    )

    private class FixedSources(
        private val accounts: List<AccountSummaryDto>,
        private val facts: Map<UUID, PartyTaxFactsDto?>,
        private val balances: Map<UUID, BigDecimal>,
    ) : CrsFatcaReportingSources {
        override suspend fun activeAccounts(limit: Int, cursor: String?): AccountPage =
            AccountPage(accounts, nextCursor = null)

        override suspend fun partyTaxFacts(partyId: UUID) = facts[partyId]

        override suspend fun bookedBalance(accountId: UUID, currency: String, asOf: LocalDate) =
            balances[accountId] ?: BigDecimal.ZERO
    }

    private val incomeCredits = mockk<IncomeCreditService>()

    private fun adapter() = CrsFatcaReturnDataAdapter(
        FixedSources(
            listOf(crsAccount, fatcaAccount, domesticAccount, noProfileAccount),
            factsByParty + (noProfileParty to null),
            mapOf(
                crsAccount.id to BigDecimal("5000.00"),
                fatcaAccount.id to BigDecimal("-100.00"),
            ),
        ),
        incomeCredits,
    )

    @Test
    fun `CRS emits one line per non-CZ residency and totals count each account once`(): Unit = runBlocking {
        coEvery { incomeCredits.totalsByAccount(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)) } returns listOf(
            IncomeCreditTotals(crsAccount.id, "CZK", BigDecimal("42.50")),
        )
        val definition = catalogue.definition("CRS")!!

        val data = adapter().fetch(catalogue, definition, "company", period)

        // A US residency is non-CZ, so the US person is CRS-reportable to the US as well as FATCA-
        // reportable — same residency logic for every country, no special case (#ADR-0340 keeps the
        // exclusion question for the wire-format increment; the totals below stay per-account).
        assertThat(data.lines).hasSize(2)
        assertThat(data.lines.map { it.fields["residency_country"] }).containsExactly("DE", "US")
        assertThat(data.lines[0].fields["tin"]).isEqualTo("123456789")
        assertThat(data.lines[0].fields["account_ref"]).isEqualTo("CZ5508000000000000000001")
        assertThat(data.lines[0].fields["period_end_balance"]).isEqualTo("5000.00")
        assertThat(data.lines[0].fields["interest_credited"]).isEqualTo("42.50")
        // Each of the two reportable accounts emits ONE residency line here, so lines and totals
        // agree; the single-currency sums add across accounts.
        assertThat(data.values["reportable_persons"]?.toInt()).isEqualTo(2)
        assertThat(data.values["reportable_accounts"]?.toInt()).isEqualTo(2)
        assertThat(data.values["total_interest_credited"]).isEqualTo(BigDecimal("42.50"))
        assertThat(data.values["total_period_end_balance"]).isEqualTo(BigDecimal("4900.00"))
        // The schema validation the assemble step will run accepts the lines and figures as-is.
        assertThat(definition.lineSchema.toSet()).isEqualTo(data.lines[0].fields.keys)
        assertThat(definition.validate(data.values)).isEmpty()
    }

    @Test
    fun `FATCA emits US lines only for US persons and tolerates an overdrawn balance`(): Unit = runBlocking {
        coEvery { incomeCredits.totalsByAccount(any(), any()) } returns emptyList()
        val definition = catalogue.definition("FATCA")!!

        val data = adapter().fetch(catalogue, definition, "company", period)

        assertThat(data.lines).hasSize(1)
        assertThat(data.lines[0].fields["residency_country"]).isEqualTo("US")
        assertThat(data.lines[0].fields["tin"]).isEqualTo("987-65-4321")
        assertThat(data.lines[0].fields["holder_name"]).isEqualTo("Us Person")
        // An overdrawn account is a legal line value — the balance datapoint stays signed.
        assertThat(data.lines[0].fields["period_end_balance"]).isEqualTo("-100.00")
        assertThat(data.values["reportable_accounts"]?.toInt()).isEqualTo(1)
        assertThat(data.values["total_period_end_balance"]).isEqualTo(BigDecimal("-100.00"))
    }

    @Test
    fun `a party without a profile and a purely domestic party emit nothing`(): Unit = runBlocking {
        coEvery { incomeCredits.totalsByAccount(any(), any()) } returns emptyList()
        val domesticOnly = CrsFatcaReturnDataAdapter(
            FixedSources(
                listOf(domesticAccount, noProfileAccount),
                factsByParty + (noProfileParty to null),
                emptyMap(),
            ),
            incomeCredits,
        )

        val data = domesticOnly.fetch(catalogue, catalogue.definition("CRS")!!, "company", period)

        assertThat(data.lines).isEmpty()
        assertThat(data.values["reportable_persons"]?.toInt()).isZero()
        assertThat(data.values["reportable_accounts"]?.toInt()).isZero()
    }

    @Test
    fun `a catalogue this adapter does not own refuses instead of guessing`(): Unit = runBlocking {
        val pension = ReturnCatalogue("cz-pension-cnb", 1, "CZ", false, emptyList())
        assertThatThrownBy {
            runBlocking { adapter().fetch(pension, catalogue.definition("CRS")!!, "company", period) }
        }.isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("cz-pension-cnb")
    }

    private fun jackson() = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().findAndRegisterModules()
}
