// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.tax.application.port.out.CorporateFactsPort
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.infrastructure.returns.pension.CompanyBooksPort
import com.openbank.tax.infrastructure.returns.pension.ParticipantSnapshotDto
import com.openbank.tax.infrastructure.returns.pension.ParticipantSnapshotReceiptDto
import com.openbank.tax.infrastructure.returns.pension.PensionFundReportingClient
import com.openbank.tax.infrastructure.returns.pension.PensionReportingClient
import com.openbank.tax.infrastructure.returns.pension.PensionReportingSources
import com.openbank.tax.infrastructure.returns.pension.PensionReturnDataAdapter
import com.openbank.tax.infrastructure.returns.pension.RestPensionReportingSources
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

class PensionClosedSourceTest {
    private val company = UUID.fromString("cae643b1-09b9-4ad7-ae55-310959d62156")
    private val snapshotId = UUID.fromString("d45ca8df-f9dc-4573-bb7b-513219f33a96")
    private val from = LocalDate.of(2026, 1, 1)
    private val to = LocalDate.of(2026, 12, 31)
    private val payload = listOf(
        """{"periodStart":"2026-01-01","periodEnd":"2026-12-31","currency":"CZK",""",
        """"participants":{"inForce":0,"contributing":0,"pensioners":0},""",
        """"contributionsYtd":{"participant":0,"employer":0,"state":0,"transferIn":0,"total":0},""",
        """"payoutsYtd":{"total":0,"cases":0,"taxWithheld":0}}""",
    ).joinToString("")
    private val sha = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val receipt = ParticipantSnapshotReceiptDto(
        snapshotId,
        company,
        from,
        to,
        1,
        "participant-period-aggregates/2.0",
        sha,
    )
    private val pension = mockk<PensionReportingClient>()
    private val sources = RestPensionReportingSources(
        mockk<PensionFundReportingClient>(),
        pension,
        jacksonObjectMapper().findAndRegisterModules(),
    )

    @Test
    fun `PSP31 legacy and immutable extraction refuse before any participant provider call`() {
        val providers = mockk<PensionReportingSources>()
        val adapter = PensionReturnDataAdapter(providers, mockk<CorporateFactsPort>(), mockk<CompanyBooksPort>())
        val catalogue = CatalogueParser.parse(jacksonObjectMapper(), "statutory-returns/cz/pension-cnb.v1.json")
        val definition = catalogue.definition("PSP31-04")!!
        val period = ReportingPeriod.parse(definition.periodicity, "2026-Q1")
        assertThatThrownBy { runBlocking { adapter.fetch(catalogue, definition, company.toString(), period) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("verified as-of beneficiary source")
        assertThatThrownBy {
            runBlocking { adapter.fetchClosed(catalogue, definition, company, period, "stable-source-key-123456") }
        }.isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("verified as-of beneficiary source")
        coVerify(exactly = 0) { providers.participants(any(), any(), any()) }
        coVerify(exactly = 0) { providers.closedParticipants(any(), any(), any(), any()) }
    }

    @Test
    fun `immutable source bytes are verified before decoding`(): Unit = runBlocking {
        coEvery { pension.captureParticipantSnapshot(any(), any()) } returns receipt
        coEvery { pension.participantSnapshot(snapshotId, company) } returns ParticipantSnapshotDto(receipt, payload)
        val verified = sources.closedParticipants(company, from, to, "stable-source-key-123456")
        assertThat(verified.receipt).isEqualTo(receipt)
        assertThat(verified.aggregates.periodEnd).isEqualTo(to)
    }

    @Test
    fun `tampered immutable payload fails closed`(): Unit = runBlocking {
        coEvery { pension.captureParticipantSnapshot(any(), any()) } returns receipt
        coEvery { pension.participantSnapshot(snapshotId, company) } returns ParticipantSnapshotDto(
            receipt,
            payload.replace("\"inForce\":0", "\"inForce\":1"),
        )
        assertThatThrownBy {
            runBlocking { sources.closedParticipants(company, from, to, "stable-source-key-123456") }
        }.isInstanceOf(ReturnDataUnavailableException::class.java).hasMessageContaining("hash")
    }

    @Test
    fun `snapshot metadata for another company fails closed`(): Unit = runBlocking {
        coEvery { pension.captureParticipantSnapshot(any(), any()) } returns receipt
        coEvery { pension.participantSnapshot(snapshotId, company) } returns ParticipantSnapshotDto(
            receipt.copy(companyId = UUID.randomUUID()),
            payload,
        )
        assertThatThrownBy {
            runBlocking { sources.closedParticipants(company, from, to, "stable-source-key-123456") }
        }.isInstanceOf(ReturnDataUnavailableException::class.java).hasMessageContaining("disagree")
    }

    @Test
    fun `matched but nonpositive source revision is unavailable before constructing lineage`(): Unit = runBlocking {
        val invalid = receipt.copy(revision = 0)
        coEvery { pension.captureParticipantSnapshot(any(), any()) } returns invalid
        coEvery { pension.participantSnapshot(snapshotId, company) } returns ParticipantSnapshotDto(invalid, payload)
        assertThatThrownBy {
            runBlocking { sources.closedParticipants(company, from, to, "stable-source-key-123456") }
        }.isInstanceOf(ReturnDataUnavailableException::class.java).hasMessageContaining("receipt")
    }

    @Test
    fun `a signed non-CZK participant close cannot be filed as a CZK return`(): Unit = runBlocking {
        val eurPayload = payload.replace("\"currency\":\"CZK\"", "\"currency\":\"EUR\"")
        val eurSha = MessageDigest.getInstance("SHA-256")
            .digest(eurPayload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val eurReceipt = receipt.copy(sha256 = eurSha)
        coEvery { pension.captureParticipantSnapshot(any(), any()) } returns eurReceipt
        coEvery { pension.participantSnapshot(snapshotId, company) } returns ParticipantSnapshotDto(
            eurReceipt,
            eurPayload,
        )
        assertThatThrownBy {
            runBlocking { sources.closedParticipants(company, from, to, "stable-source-key-123456") }
        }.isInstanceOf(ReturnDataUnavailableException::class.java).hasMessageContaining("non-CZK")
    }
}
