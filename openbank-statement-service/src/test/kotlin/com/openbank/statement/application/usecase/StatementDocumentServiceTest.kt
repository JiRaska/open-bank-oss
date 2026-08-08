// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.openbank.statement.Fixtures
import com.openbank.statement.application.port.`in`.StatementModelUseCase
import com.openbank.statement.application.port.out.DocumentServiceException
import com.openbank.statement.application.port.out.DocumentTemplatePort
import com.openbank.statement.application.port.out.RenderedDocument
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class StatementDocumentServiceTest {

    private val statementModel = mockk<StatementModelUseCase>()
    private val documentTemplates = mockk<DocumentTemplatePort>()
    private val service = StatementDocumentService(statementModel, documentTemplates)

    @Test
    fun `renderDocument replays the statement model and calls the CS template for a cs locale`() {
        every { statementModel.statementModel(Fixtures.ACCOUNT_ID, "CZK", 7L) } returns
            Uni.createFrom().item(Fixtures.model())
        every { documentTemplates.renderTemplate(any(), any()) } returns
            Uni.createFrom().item(RenderedDocument("text/html; charset=utf-8", "<html></html>"))

        val result = service.renderDocument(Fixtures.ACCOUNT_ID, "CZK", 7L, "cs").await().indefinitely()

        assertThat(result.body).isEqualTo("<html></html>")
        verify(exactly = 1) { documentTemplates.renderTemplate("MESICNI_VYPIS_CS", any()) }
    }

    @Test
    fun `renderDocument calls the EN template for any non-cs locale`() {
        every { statementModel.statementModel(any(), any(), any()) } returns Uni.createFrom().item(Fixtures.model())
        every { documentTemplates.renderTemplate(any(), any()) } returns
            Uni.createFrom().item(RenderedDocument("text/html; charset=utf-8", "<html></html>"))

        service.renderDocument(Fixtures.ACCOUNT_ID, "CZK", 7L, "en").await().indefinitely()
        verify(exactly = 1) { documentTemplates.renderTemplate("MESICNI_VYPIS_EN", any()) }
    }

    @Test
    fun `a missing closed period fails before any document-service call is made`() {
        every { statementModel.statementModel(any(), any(), any()) } returns
            Uni.createFrom().failure(StatementNotFoundException(Fixtures.ACCOUNT_ID, "CZK", 99L))

        assertThatThrownBy {
            service.renderDocument(Fixtures.ACCOUNT_ID, "CZK", 99L, "cs").await().indefinitely()
        }.isInstanceOf(StatementNotFoundException::class.java)
        verify(exactly = 0) { documentTemplates.renderTemplate(any(), any()) }
    }

    @Test
    fun `a document-service failure propagates as DocumentServiceException`() {
        every { statementModel.statementModel(any(), any(), any()) } returns Uni.createFrom().item(Fixtures.model())
        every { documentTemplates.renderTemplate(any(), any()) } returns
            Uni.createFrom().failure(DocumentServiceException("preview call failed"))

        assertThatThrownBy {
            service.renderDocument(Fixtures.ACCOUNT_ID, "CZK", 7L, "cs").await().indefinitely()
        }.isInstanceOf(DocumentServiceException::class.java)
    }

    @Test
    fun `toDocumentData maps the statement model into the document, party and account namespaces`() {
        val model = Fixtures.model()

        val data = model.toDocumentData()

        @Suppress("UNCHECKED_CAST")
        val document = data["document"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val party = data["party"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val account = data["account"] as Map<String, Any?>

        assertThat(document["periodFrom"]).isEqualTo("2026-01-01")
        assertThat(document["periodTo"]).isEqualTo("2026-01-31")
        assertThat(document["openingBalance"]).isEqualTo(BigDecimal("1000.00"))
        assertThat(document["closingBalance"]).isEqualTo(BigDecimal("1075.00"))
        assertThat(document["legalSequenceNumber"]).isEqualTo(7L)
        assertThat(document["electronicSequenceNumber"]).isEqualTo(7L)
        // closedAt, never wall clock (ADR-0035 determinism guarantee extended to this template).
        assertThat(document["generatedAt"]).isEqualTo("2026-02-01T02:30:00Z")
        assertThat(party["name"]).isEqualTo("Jan Novak")
        assertThat(account["iban"]).isEqualTo("CZ6508000000192000145399")
        assertThat(account["currency"]).isEqualTo("CZK")

        @Suppress("UNCHECKED_CAST")
        val entries = document["entries"] as List<Map<String, Any?>>
        assertThat(entries).hasSize(2)
        // TX-1 is a credit -> positive; TX-2 is a debit -> negative (the domain amount is always
        // non-negative, the sign is carried separately by creditDebit).
        assertThat(entries[0]["amount"]).isEqualTo(BigDecimal("100.00"))
        assertThat(entries[1]["amount"]).isEqualTo(BigDecimal("-25.00"))
        assertThat(entries[0]["bookingDate"]).isEqualTo("2026-01-15")
        assertThat(entries[0]["currency"]).isEqualTo("CZK")
        assertThat(entries[1]["description"]).isEqualTo("Fee")
    }
}
