// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.AnnualFeeLine
import com.openbank.document.application.port.`in`.AnnualFeeSummaryReadyCommand
import com.openbank.document.application.port.`in`.DocumentTemplateUseCase
import com.openbank.document.application.port.out.AccountInfo
import com.openbank.document.application.port.out.AccountLookupPort
import com.openbank.document.application.port.out.PartyInfo
import com.openbank.document.application.port.out.PartyLookupPort
import com.openbank.document.application.port.out.StatementDeliveryPort
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Mapping + idempotency coverage for [AnnualStatementDeliveryService] (ADR-0248). The rendering
 * pipeline itself ([DocumentTemplateUseCase.previewRender]) is mocked — this test's job is to
 * verify the event-to-Handlebars-data mapping and the (accountId, year) replay guard, not
 * Handlebars merge behaviour (that's [com.openbank.document.infrastructure.render]'s job).
 */
class AnnualStatementDeliveryServiceTest {

    private val templateRepo: TemplateRepositoryPort = mockk()
    private val templateUseCase: DocumentTemplateUseCase = mockk()
    private val partyLookupPort: PartyLookupPort = mockk()
    private val accountLookupPort: AccountLookupPort = mockk()
    private val deliveryPort: StatementDeliveryPort = mockk(relaxed = true)
    private val idempotencyStore: IdempotencyStore = mockk()
    private val service = AnnualStatementDeliveryService(
        templateRepo,
        templateUseCase,
        partyLookupPort,
        accountLookupPort,
        deliveryPort,
        idempotencyStore,
    )

    private val accountId: UUID = UUID.randomUUID()
    private val partyId: UUID = UUID.randomUUID()
    private val idempotencyKey = "annual-statement:$accountId:2026"

    private fun template() = DocumentTemplate(
        id = UUID.randomUUID(),
        code = "ROCNI_VYPIS_POPLATKU_CS",
        version = "1.0.0",
        name = "Roční výpis poplatků",
        engine = TemplateEngine.HANDLEBARS,
        bodyHtml = "<p>{{document.year}}</p>",
        locale = "cs",
        status = TemplateStatus.PUBLISHED,
        productRef = null,
        classification = "restricted",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        createdBy = "system",
    )

    private fun command() = AnnualFeeSummaryReadyCommand(
        accountId = accountId,
        partyRef = partyId.toString(),
        year = 2026,
        currency = "CZK",
        fees = listOf(
            AnnualFeeLine(name = "Account maintenance", category = "MAINTENANCE", amount = BigDecimal("120.00")),
            AnnualFeeLine(name = "Card issuance", category = "CARD", amount = BigDecimal("300.00")),
        ),
        totalFees = BigDecimal("420.00"),
        interestRate = BigDecimal("0.50"),
    )

    @Test
    fun `renders the published CS template with the mapped data and delivers it`(): Unit = runBlocking {
        coEvery { idempotencyStore.get(idempotencyKey) } returns null
        coEvery { templateRepo.findLatestPublished("ROCNI_VYPIS_POPLATKU_CS") } returns template()
        coEvery { partyLookupPort.findById(partyId) } returns
            PartyInfo(legalName = "Jan Novák", formattedAddress = "Praha 1")
        coEvery { accountLookupPort.findCurrentAccount(partyId) } returns
            AccountInfo(iban = "CZ6508000000192000145399", productId = UUID.randomUUID())
        val dataSlot = slot<Map<String, Any?>>()
        every { templateUseCase.previewRender(any(), capture(dataSlot)) } returns "<p>2026</p>"
        coEvery { idempotencyStore.save(any(), any(), any(), any()) } returns Unit

        service.deliverAnnualStatement(command())

        @Suppress("UNCHECKED_CAST")
        val document = dataSlot.captured["document"] as Map<String, Any?>
        assertThat(document["year"]).isEqualTo(2026)
        assertThat(document["totalFees"]).isEqualTo("420.00")
        assertThat(document["interestRate"]).isEqualTo("0.50")
        @Suppress("UNCHECKED_CAST")
        val fees = document["fees"] as List<Map<String, Any?>>
        assertThat(fees).containsExactly(
            mapOf("name" to "Account maintenance", "category" to "MAINTENANCE", "amount" to "120.00"),
            mapOf("name" to "Card issuance", "category" to "CARD", "amount" to "300.00"),
        )

        @Suppress("UNCHECKED_CAST")
        val party = dataSlot.captured["party"] as Map<String, Any?>
        assertThat(party["name"]).isEqualTo("Jan Novák")
        @Suppress("UNCHECKED_CAST")
        val account = dataSlot.captured["account"] as Map<String, Any?>
        assertThat(account["iban"]).isEqualTo("CZ6508000000192000145399")

        verify(exactly = 1) {
            deliveryPort.deliver(
                partyRef = partyId.toString(),
                documentBytes = "<p>2026</p>".toByteArray(Charsets.UTF_8),
                contentType = "text/html",
                subject = "Annual statement of fees 2026 — account $accountId",
            )
        }
        coVerify(exactly = 1) { idempotencyStore.save(idempotencyKey, 200, "delivered", any()) }
    }

    @Test
    fun `skips delivery when the same account-year pair was already delivered`(): Unit = runBlocking {
        coEvery { idempotencyStore.get(idempotencyKey) } returns
            IdempotencyRecord(idempotencyKey, 200, "delivered", OffsetDateTime.now())

        service.deliverAnnualStatement(command())

        coVerify(exactly = 0) { templateRepo.findLatestPublished(any()) }
        verify(exactly = 0) { deliveryPort.deliver(any(), any(), any(), any()) }
    }

    @Test
    fun `skips delivery when no PUBLISHED template exists, without throwing`(): Unit = runBlocking {
        coEvery { idempotencyStore.get(idempotencyKey) } returns null
        coEvery { templateRepo.findLatestPublished("ROCNI_VYPIS_POPLATKU_CS") } returns null

        service.deliverAnnualStatement(command())

        verify(exactly = 0) { deliveryPort.deliver(any(), any(), any(), any()) }
        coVerify(exactly = 0) { idempotencyStore.save(any(), any(), any(), any()) }
    }

    @Test
    fun `treats a null interestRate as absent rather than as zero`(): Unit = runBlocking {
        coEvery { idempotencyStore.get(idempotencyKey) } returns null
        coEvery { templateRepo.findLatestPublished("ROCNI_VYPIS_POPLATKU_CS") } returns template()
        coEvery { partyLookupPort.findById(partyId) } returns null
        coEvery { accountLookupPort.findCurrentAccount(partyId) } returns null
        val dataSlot = slot<Map<String, Any?>>()
        every { templateUseCase.previewRender(any(), capture(dataSlot)) } returns "<p></p>"
        coEvery { idempotencyStore.save(any(), any(), any(), any()) } returns Unit

        service.deliverAnnualStatement(command().copy(interestRate = null))

        @Suppress("UNCHECKED_CAST")
        val document = dataSlot.captured["document"] as Map<String, Any?>
        assertThat(document["interestRate"]).isNull()
    }
}
