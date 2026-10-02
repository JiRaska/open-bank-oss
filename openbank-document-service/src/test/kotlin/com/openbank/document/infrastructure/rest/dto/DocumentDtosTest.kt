// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest.dto

import com.openbank.document.application.port.`in`.OnboardingAgreement
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The wire projections. Worth holding because they are hand-written field lists: a field added to
 * the aggregate and forgotten here silently disappears from every API response, and the REST tests
 * assert only on the fields they happen to name.
 */
class DocumentDtosTest {

    private val now: Instant = Instant.now()

    @Test
    fun `DocumentResponse carries every aggregate field the API exposes`() {
        val document = Document(
            id = UUID.randomUUID(),
            templateCode = "VOP",
            templateVersion = "2.0.0",
            sha256 = "c".repeat(64),
            storageKey = "document/rendered/x",
            contentType = "application/pdf",
            sizeBytes = 2048,
            status = DocumentStatus.SIGNED,
            metadata = mapOf("lang" to "cs"),
            partyRef = "party-1",
            caseRef = "case-1",
            productRef = "prod-1",
            retainUntil = LocalDate.of(2040, 6, 1),
            createdAt = now,
            idempotencyKey = "onboarding:acc-1",
        )

        val response = document.toResponse()

        assertThat(response.id).isEqualTo(document.id)
        assertThat(response.templateCode).isEqualTo("VOP")
        assertThat(response.templateVersion).isEqualTo("2.0.0")
        assertThat(response.sha256).isEqualTo(document.sha256)
        assertThat(response.storageKey).isEqualTo("document/rendered/x")
        assertThat(response.contentType).isEqualTo("application/pdf")
        assertThat(response.sizeBytes).isEqualTo(2048L)
        assertThat(response.status).isEqualTo(DocumentStatus.SIGNED)
        assertThat(response.metadata).containsEntry("lang", "cs")
        assertThat(response.partyRef).isEqualTo("party-1")
        assertThat(response.caseRef).isEqualTo("case-1")
        assertThat(response.productRef).isEqualTo("prod-1")
        assertThat(response.retainUntil).isEqualTo(LocalDate.of(2040, 6, 1))
        assertThat(response.createdAt).isEqualTo(now)
    }

    @Test
    fun `TemplateResponse exposes the body and publication state but never createdBy`() {
        val template = DocumentTemplate(
            id = UUID.randomUUID(),
            code = "RAMCOVA_SMLOUVA",
            version = "1.0.0",
            name = "Framework agreement",
            engine = TemplateEngine.HANDLEBARS,
            bodyHtml = "<p>x</p>",
            locale = "cs",
            status = TemplateStatus.DRAFT,
            productRef = null,
            classification = "restricted",
            createdAt = now,
            createdBy = "operator-1",
        )

        val response = template.toResponse()

        assertThat(response.code).isEqualTo("RAMCOVA_SMLOUVA")
        assertThat(response.engine).isEqualTo(TemplateEngine.HANDLEBARS)
        assertThat(response.status).isEqualTo(TemplateStatus.DRAFT)
        assertThat(response.bodyHtml).isEqualTo("<p>x</p>")
        assertThat(response.productRef).isNull()
        assertThat(TemplateResponse::class.java.declaredFields.map { it.name }).doesNotContain("createdBy")
    }

    @Test
    fun `OnboardingAgreement projects the ceremony, document and its content address`() {
        val agreement = OnboardingAgreement(
            ceremonyId = UUID.randomUUID(),
            documentId = UUID.randomUUID(),
            templateCode = "RAMCOVA_SMLOUVA",
            templateVersion = "1.0.0",
            sha256 = "d".repeat(64),
            documentStatus = DocumentStatus.PENDING_SIGNATURE,
        )

        val response = agreement.toResponse()

        assertThat(response.ceremonyId).isEqualTo(agreement.ceremonyId)
        assertThat(response.documentId).isEqualTo(agreement.documentId)
        assertThat(response.sha256).isEqualTo(agreement.sha256)
        assertThat(response.documentStatus).isEqualTo(DocumentStatus.PENDING_SIGNATURE)
    }

    @Test
    fun `request defaults match the documented contract`() {
        val render = RenderDocumentRequest(templateCode = "VOP")

        // null version = "render the current PUBLISHED version" (ADR-0162 resolution policy),
        // NOT a missing value the caller must supply.
        assertThat(render.templateVersion).isNull()
        assertThat(render.contentType).isEqualTo("application/pdf")
        assertThat(render.data).isEmpty()

        val template = CreateTemplateRequest(code = "VOP", version = "1.0.0", name = "VOP", bodyHtml = "<p/>")
        assertThat(template.engine).isEqualTo(TemplateEngine.HANDLEBARS)
        assertThat(template.locale).isEqualTo("en")
        assertThat(template.classification).isEqualTo("restricted")

        assertThat(EnsureOnboardingAgreementRequest(partyRef = "p1").lang).isEqualTo("cs")
    }
}
