// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.port.out

import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.util.UUID

/** Persistence port for the [DocumentTemplate] aggregate. */
interface TemplateRepositoryPort {
    suspend fun save(template: DocumentTemplate): DocumentTemplate
    suspend fun findById(id: UUID): DocumentTemplate?
    suspend fun findPublished(code: String, version: String): DocumentTemplate?

    // Named findAllTemplates, not listAll: a Panache-backed implementor already inherits
    // PanacheRepository's own zero-arg `listAll(): Uni<List<Entity>>` — same erased JVM signature,
    // different return type — so naming this port method `listAll()` too is not implementable
    // (pre-existing scaffold defect, fixed while wiring the real Postgres/S3 adapters).
    //
    // Bounded, not cursor-paginated: [limit] caps the result size so a growing template catalog
    // can never make this an unbounded table scan. True cursor pagination (CursorPage, the
    // platform convention used by account-service/ledger-service) is a follow-up if/when the
    // catalog needs stable forward paging past one bounded page.
    suspend fun findAllTemplates(limit: Int): List<DocumentTemplate>
}

/** Persistence port for the [Document] aggregate, including transactional-outbox co-persistence. */
interface DocumentRepositoryPort {
    suspend fun save(document: Document): Document
    suspend fun saveWithOutbox(document: Document, outboxMessage: OutboxMessage): Document
    suspend fun findById(id: UUID): Document?
    suspend fun findByParty(partyRef: String): List<Document>
}

/** Persistence port for the [SignatureCeremony] aggregate. */
interface CeremonyRepositoryPort {
    suspend fun save(ceremony: SignatureCeremony): SignatureCeremony
    suspend fun saveWithOutbox(ceremony: SignatureCeremony, outboxMessage: OutboxMessage): SignatureCeremony
    suspend fun findById(id: UUID): SignatureCeremony?
}

/**
 * Merges a data map into a template body and returns the rendered HTML. Logic-less by contract:
 * the production adapter (Handlebars, ADR-0162 D2) does no arbitrary code execution — only
 * `{{token}}` substitution with default HTML escaping.
 */
interface TemplateRenderPort {
    fun renderHtml(template: DocumentTemplate, data: Map<String, Any?>): String
}

/**
 * Converts rendered HTML to a PDF byte stream. Production adapter = WeasyPrint sidecar (default) /
 * Gotenberg (opt-in) over REST behind this port (ADR-0162 D3).
 */
interface PdfRenderPort {
    suspend fun htmlToPdf(html: String): ByteArray
}

/**
 * Applies a PAdES seal to a signed PDF. Phase-1 (this port's current adapter) = a server-applied
 * PAdES-B seal with an organizational (or, in dev, ephemeral) certificate; phase-2 = EU DSS
 * PAdES-LTA sealing with a QSeal/HSM key (ADR-0007, ADR-0162 D4).
 */
interface SignatureSealPort {
    suspend fun sealPades(pdf: ByteArray, ceremony: SignatureCeremony): ByteArray
}

/**
 * SCA-bound strong-authentication check gating a SIGNED decision (ADR-0162 D4, ADR-0021). A
 * signer's decision is only accepted as a legally meaningful signature once the [evidenceRef] —
 * a completed SCA challenge/approval reference for [partyRef] — has been verified. A DECLINED
 * decision does not need this check (declining requires no elevated assurance).
 */
interface SignerVerificationPort {
    suspend fun verify(partyRef: String, evidenceRef: String): Boolean
}
