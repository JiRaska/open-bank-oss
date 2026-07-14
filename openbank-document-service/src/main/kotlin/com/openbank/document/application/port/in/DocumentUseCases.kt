// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.port.`in`

import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.SignerStatus
import com.openbank.document.domain.model.TemplateEngine
import java.time.LocalDate
import java.util.UUID

data class CreateTemplateCommand(
    val code: String,
    val version: String,
    val name: String,
    val engine: TemplateEngine,
    val bodyHtml: String,
    val locale: String,
    val productRef: String?,
    val classification: String,
    val createdBy: String,
)

data class RenderDocumentCommand(
    val templateCode: String,
    // Null resolves to the current PUBLISHED version for [templateCode] (ADR-0162
    // version-resolution policy) -- the caller pins an exact version only when it deliberately
    // needs one that isn't current (e.g. re-rendering against a historical version for a support
    // case). The [com.openbank.document.domain.model.Document] this produces always snapshots the
    // exact version it actually resolved to, so the render itself is never ambiguous after the fact.
    val templateVersion: String?,
    val data: Map<String, Any?>,
    val contentType: String,
    val partyRef: String?,
    val caseRef: String?,
    val productRef: String?,
    val retainUntil: LocalDate?,
)

data class OpenCeremonyCommand(
    val documentId: UUID,
    val signerPartyRefs: List<String>,
    val signatureLevel: SignatureLevel,
)

/** Authoring and publication of document templates. */
interface DocumentTemplateUseCase {
    suspend fun createTemplate(cmd: CreateTemplateCommand): DocumentTemplate
    suspend fun publishTemplate(id: UUID): DocumentTemplate
    suspend fun retireTemplate(id: UUID): DocumentTemplate
    suspend fun getTemplate(id: UUID): DocumentTemplate?

    /** [limit] is capped server-side (see [com.openbank.document.application.usecase.DocumentTemplateService]) — always bounded, never a full table scan. */
    suspend fun listTemplates(limit: Int): List<DocumentTemplate>

    /**
     * Merges [sampleData] into [bodyHtml] and returns the resulting HTML — the "dynamic preview"
     * capability: an author sees the *actual* Handlebars-merged output while editing an unsaved
     * DRAFT, not just the raw markup. Pure, no persistence, no PDF rendering (that stays behind
     * [DocumentRenderUseCase] for a real, stored [Document]). Not suspend: it does no I/O.
     */
    fun previewRender(bodyHtml: String, sampleData: Map<String, Any?>): String
}

/** Renders a document from a published template + a data map, storing it and emitting an event. */
interface DocumentRenderUseCase {
    suspend fun render(cmd: RenderDocumentCommand): Document
}

/** Reads document metadata and content bytes. */
interface DocumentQueryUseCase {
    suspend fun getMetadata(id: UUID): Document?
    suspend fun getContent(id: UUID): ByteArray?

    /** Documents for a party/case — lets the compliance/legal persona browse, not just look up by id. */
    suspend fun listByParty(partyRef: String): List<Document>
}

/** Opens signing ceremonies and records signer decisions. */
interface SignatureCeremonyUseCase {
    suspend fun openCeremony(cmd: OpenCeremonyCommand): SignatureCeremony

    /**
     * [evidenceRef] is the SCA challenge/approval reference (ADR-0021) proving the signer's
     * identity/consent for this decision; required and verified (via [com.openbank.document.application.port.out.SignerVerificationPort])
     * when [decision] is SIGNED, not required for DECLINED.
     */
    suspend fun recordDecision(
        ceremonyId: UUID,
        partyRef: String,
        decision: SignerStatus,
        evidenceRef: String? = null,
    ): SignatureCeremony
    suspend fun getCeremony(id: UUID): SignatureCeremony?
}
