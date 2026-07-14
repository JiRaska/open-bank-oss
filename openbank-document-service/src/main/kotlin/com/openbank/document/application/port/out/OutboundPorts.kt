// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.port.out

import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.util.UUID

/**
 * Thrown by [TemplateRepositoryPort.publishReplacing] when a concurrent publish of the same
 * template `code` lost the race (a Postgres unique-violation on the partial index that enforces
 * "at most one PUBLISHED row per code", translated at the persistence-adapter boundary so the
 * application layer never depends on a framework/SQL exception type — ADR-0002 layering).
 */
class TemplatePublishConflictException(message: String) : RuntimeException(message)

/** Persistence port for the [DocumentTemplate] aggregate. */
interface TemplateRepositoryPort {
    suspend fun save(template: DocumentTemplate): DocumentTemplate
    suspend fun findById(id: UUID): DocumentTemplate?
    suspend fun findPublished(code: String, version: String): DocumentTemplate?

    /**
     * The current PUBLISHED version for [code] — the "latest" a new render/product reference
     * resolves to when it pins no exact version (ADR-0162 version-resolution policy). At most one
     * row can be PUBLISHED per code at a time (enforced by [publishReplacing] plus a DB partial
     * unique index, `uq_document_templates_one_published_per_code`), so this is a lookup, not a
     * ranking — the tie-break sort only matters transiently, mid-[publishReplacing], or for
     * pre-existing data from before that invariant was enforced.
     */
    suspend fun findLatestPublished(code: String): DocumentTemplate?

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

    /**
     * Publishes [toPublish] and, if [toRetire] is non-null, retires it in the SAME transaction —
     * so a code's predecessor is never left PUBLISHED alongside its successor, nor briefly
     * unpublished, even if the process crashes mid-operation (ADR-0162 version-resolution policy:
     * publishing a version supersedes, and immediately retires, whatever it replaces).
     */
    suspend fun publishReplacing(toPublish: DocumentTemplate, toRetire: DocumentTemplate?): DocumentTemplate
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

/**
 * Applies [partyRef]'s own **electronic signature** (eIDAS terms: a natural person's signature,
 * distinct from [SignatureSealPort]'s institutional **electronic seal**) to [pdf] — a fresh,
 * single-use certificate is issued for this one signing act and the private key is never
 * persisted beyond it (ADR-0162 D4 continued). Unlike the seal's stable organizational identity,
 * the *value* being protected here is that this specific act was authorized by a certificate
 * freshly minted for it — the chain of trust (who issued it, when, to whom) is what stays
 * auditable, rooted in the issuing CA (production: OpenBao's PKI secrets engine), not the leaf
 * certificate's own lifetime.
 */
interface ClientSignatureIssuerPort {
    suspend fun signAsClient(pdf: ByteArray, partyRef: String): ByteArray
}

/**
 * Read-only lookup into `openbank-product-catalog`, scoped to exactly what the onboarding flow
 * needs: which document template (if any) is bound to a product, via `TermsAndConditions.documentTemplateCode`
 * (a `code`, never a pinned version — ADR-0162 D1/version-resolution policy). Fails open: an
 * unreachable/unknown product-catalog resolves to `null`, same fail-open stance
 * `account-service`'s own `ProductCatalogPort` takes for the same reference-data dependency —
 * a missing onboarding-document binding must never block account opening (that already happened,
 * upstream, by the time this is consulted).
 */
interface ProductCatalogPort {
    suspend fun findDocumentTemplateCode(productId: UUID): String?
}
