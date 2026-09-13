// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.port.`in`

import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.SignerStatus
import com.openbank.document.domain.model.TemplateEngine
import java.math.BigDecimal
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
    // Onboarding sets this ("onboarding:<accountId>") to make issuance idempotent under at-least-once
    // redelivery (ADR-0162 D7); ad-hoc REST renders leave it null. See [Document.idempotencyKey].
    val idempotencyKey: String? = null,
)

data class OpenCeremonyCommand(
    val documentId: UUID,
    val signerPartyRefs: List<String>,
    val signatureLevel: SignatureLevel,
)

data class IssueOnboardingDocumentCommand(val accountId: UUID, val partyRef: String, val productId: UUID)

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

    /**
     * EVERY document of a party, unbounded — the onboarding-agreement resolution only (see
     * [com.openbank.document.application.port.out.DocumentRepositoryPort.findByParty]).
     * The REST browse path must use [listByPartyPaged] (#8082).
     */
    suspend fun listByParty(partyRef: String): List<Document>

    /** One page of a party's documents, newest first — the compliance/legal browse (#8082). */
    suspend fun listByPartyPaged(partyRef: String, page: Int, size: Int): List<Document>

    /** Total documents for [partyRef], so a caller can tell a full page from the whole set. */
    suspend fun countByParty(partyRef: String): Long

    /** The document previously issued under [key] (e.g. onboarding idempotency key), or null. */
    suspend fun findByIdempotencyKey(key: String): Document?
}

/** Opens signing ceremonies and records signer decisions. */
interface SignatureCeremonyUseCase {
    suspend fun openCeremony(cmd: OpenCeremonyCommand): SignatureCeremony

    /** The ceremony over [documentId] if one exists (resumable onboarding: open one only if absent). */
    suspend fun findByDocumentId(documentId: UUID): SignatureCeremony?

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

/**
 * Reacts to a new account (ADR-0086 event-driven, non-money-path — never a synchronous gate on
 * account opening): renders the product's bound onboarding document (if any) and opens a
 * signature ceremony for the account holder. Idempotent — safe under at-least-once Kafka delivery
 * / replay (see [com.openbank.document.application.usecase.OnboardingDocumentService]).
 */
interface OnboardingDocumentUseCase {
    suspend fun issueOnboardingDocument(cmd: IssueOnboardingDocumentCommand)

    /**
     * Get-or-create the party's onboarding framework agreement in [lang] (ADR-0169 D3), the
     * customer-driven, language-correct counterpart to the event-driven [issueOnboardingDocument].
     * Idempotent: an already-SIGNED agreement (any language) is returned untouched; a pending
     * agreement already in [lang] is returned as-is; a pending agreement in a *different* language
     * is superseded (archived) and re-rendered in [lang], since language is still changeable before
     * signing. [lang] is an ISO-639-1 code (`cs`/`en`); anything else falls back to the default.
     */
    suspend fun ensureOnboardingAgreement(partyRef: String, lang: String): OnboardingAgreement
}

/** One fee line of an [AnnualFeeSummaryReadyCommand] — the subset the ROCNI_VYPIS_POPLATKU template needs. */
data class AnnualFeeLine(val name: String, val category: String, val amount: BigDecimal)

/**
 * The `AnnualFeeSummaryReady` billing-outbox event, parsed and validated by
 * [com.openbank.document.infrastructure.kafka.AnnualFeeSummaryReadyConsumer] (ADR-0248 — event
 * contract is authoritative and shared with `billing-service`'s producer side; do not evolve this
 * shape without updating both). `interestRate` is nullable because the source event may omit it.
 */
data class AnnualFeeSummaryReadyCommand(
    val accountId: UUID,
    val partyRef: String,
    val year: Int,
    val currency: String,
    val fees: List<AnnualFeeLine>,
    val totalFees: BigDecimal,
    val interestRate: BigDecimal?,
)

/**
 * Reacts to `AnnualFeeSummaryReady` (ADR-0248): renders the year's ROCNI_VYPIS_POPLATKU statement
 * via the non-persisting preview path (never stored, never emits `document.generated` — a PAD
 * Art. 5 push duty, not a document a customer requests later) and hands the bytes to
 * [com.openbank.document.application.port.out.StatementDeliveryPort]. Idempotent per
 * (accountId, year) under at-least-once Kafka redelivery.
 */
interface AnnualStatementDeliveryUseCase {
    suspend fun deliverAnnualStatement(cmd: AnnualFeeSummaryReadyCommand)
}

/**
 * Result of [OnboardingDocumentUseCase.ensureOnboardingAgreement]: the ceremony the customer signs
 * plus the exact rendered framework-agreement document it is scoped to (content-addressed by
 * [sha256], the value the SCA challenge must bind to — ADR-0169 D2).
 */
data class OnboardingAgreement(
    val ceremonyId: UUID,
    val documentId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val documentStatus: DocumentStatus,
)
