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

/**
 * A document with the same idempotency key already exists — a concurrent onboarding delivery lost
 * the insert race (Postgres unique-violation on `uq_documents_idempotency_key`, translated at the
 * persistence boundary so the application layer never depends on a framework/SQL exception type,
 * ADR-0002). Extends [IllegalStateException] so the shared runtime maps an *uncaught* one to 422;
 * onboarding catches it and resolves to the winning document (idempotent).
 */
class DuplicateDocumentException(message: String) : IllegalStateException(message)

/**
 * A non-terminal ceremony already exists for the document — a concurrent open lost the race
 * (unique-violation on `uq_signature_ceremonies_active_document`). Same boundary-translation and
 * 422-mapping rationale as [DuplicateDocumentException].
 */
class DuplicateCeremonyException(message: String) : IllegalStateException(message)

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

    /**
     * Persists [document] with its outbox event in one transaction. Throws [DuplicateDocumentException]
     * if [Document.idempotencyKey] collides with an existing row (a lost onboarding-redelivery race).
     */
    suspend fun saveWithOutbox(document: Document, outboxMessage: OutboxMessage): Document
    suspend fun findById(id: UUID): Document?

    /**
     * EVERY document of a party, unbounded. Reserved for the onboarding-agreement resolution in
     * `OnboardingDocumentService.ensureOnboardingAgreement`, which filters the whole set for the
     * party's one live framework agreement and archives what it supersedes: bounding that read
     * would let an already-SIGNED agreement fall off the page, and step 1 would re-render a
     * contract that is legally signed. The filter makes the working set small by construction.
     *
     * NOT for the REST browse path — a party's document count is caller-controlled and unbounded.
     * Use [findByPartyPaged] there (#8082).
     */
    suspend fun findByParty(partyRef: String): List<Document>

    /** One page of a party's documents, newest first, for the bounded browse contract (#8082). */
    suspend fun findByPartyPaged(partyRef: String, page: Int, size: Int): List<Document>

    /**
     * Rows [findByPartyPaged] is paging over. Without it a caller cannot tell "this is everything"
     * from "this is the first page of many" — the two render identically and mean opposite things
     * to whoever is judging whether they have seen a party's whole file.
     */
    suspend fun countByParty(partyRef: String): Long

    /** The document persisted under [idempotencyKey], or null — an O(1) index lookup, not a scan. */
    suspend fun findByIdempotencyKey(idempotencyKey: String): Document?
}

/** Persistence port for the [SignatureCeremony] aggregate. */
interface CeremonyRepositoryPort {
    /**
     * Inserts a new ceremony or updates an existing one (optimistic-locked). Throws
     * [DuplicateCeremonyException] if a non-terminal ceremony already exists for the same document
     * (a lost open race), and [IllegalStateException] on an optimistic-lock conflict.
     */
    suspend fun save(ceremony: SignatureCeremony): SignatureCeremony
    suspend fun saveWithOutbox(ceremony: SignatureCeremony, outboxMessage: OutboxMessage): SignatureCeremony
    suspend fun findById(id: UUID): SignatureCeremony?

    /** The (non-terminal-or-not) ceremony over [documentId], or null. */
    suspend fun findByDocumentId(documentId: UUID): SignatureCeremony?
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
 *
 * [documentSha256]/[ceremonyId] scope the check to the exact document + ceremony the device
 * signed (RTS Art. 5 dynamic linking, ADR-0169 D2): an evidenceRef approved for a different
 * document must not verify here, even if it belongs to the same [partyRef].
 */
interface SignerVerificationPort {
    suspend fun verify(
        partyRef: String,
        evidenceRef: String,
        documentSha256: String? = null,
        ceremonyId: String? = null,
    ): Boolean
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
    /**
     * Apply [partyRef]'s electronic signature to [pdf].
     *
     * [visual] is the human-readable evidence to draw onto the page before signing — a PAdES
     * signature is otherwise invisible in ordinary viewers, so a customer's signed contract looks
     * unsigned. Passing null signs without a visible block (used where no signer identity is
     * available to display).
     */
    suspend fun signAsClient(pdf: ByteArray, partyRef: String, document: SignedDocumentRef? = null): ByteArray
}

/**
 * Identifies the document being signed, for the visible signature block. Null means "sign without a
 * visible block" — the signer's name and the signing time are resolved by the adapter.
 */
data class SignedDocumentRef(val documentId: String, val fingerprint: String)

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

    /**
     * The product's display name + code, for the RAMCOVA_SMLOUVA template's `{{product.name}}`/
     * `{{product.code}}` clause (Article 2). Same fail-open stance as [findDocumentTemplateCode] —
     * that clause is itself `{{#if}}`-guarded in the template, so a lookup failure degrades to
     * omitting the clause, never to blocking the signature.
     */
    suspend fun findProduct(productId: UUID): ProductInfo?
}

data class ProductInfo(val name: String?, val code: String)

/**
 * Read-only lookup into `openbank-party-service`, scoped to what RAMCOVA_SMLOUVA's `{{party.*}}`
 * clause needs (the customer's own name + on-file address, ADR-0169 D5). Fails open, matching
 * [ProductCatalogPort]: `party.name` is the one placeholder the template does NOT `{{#if}}`-guard,
 * so a lookup failure still renders a legible (if impersonal) contract rather than blocking the
 * signature over an enrichment dependency — but see [OnboardingDocumentService] for the fallback
 * used when this returns `null`.
 */
interface PartyLookupPort {
    suspend fun findById(partyId: UUID): PartyInfo?
}

data class PartyInfo(val legalName: String, val formattedAddress: String?)

/**
 * Read-only lookup into `openbank-account-service`, scoped to the CURRENT account RAMCOVA_SMLOUVA's
 * `{{account.iban}}`/`{{product.*}}` clause needs. By the time the framework agreement is signed
 * the account already exists (ADR-0162 D7 — `account.created` fires before the sign step), so this
 * is real on-file data. Fails open like [ProductCatalogPort] — the clause is `{{#if}}`-guarded.
 */
interface AccountLookupPort {
    suspend fun findCurrentAccount(partyId: UUID): AccountInfo?
}

data class AccountInfo(val iban: String, val productId: UUID)
