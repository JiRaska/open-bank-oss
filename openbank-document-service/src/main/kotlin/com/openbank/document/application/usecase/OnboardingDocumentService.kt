// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.IssueOnboardingDocumentCommand
import com.openbank.document.application.port.`in`.OnboardingAgreement
import com.openbank.document.application.port.`in`.OnboardingDocumentUseCase
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.RenderDocumentCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.application.port.out.AccountLookupPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.DuplicateCeremonyException
import com.openbank.document.application.port.out.DuplicateDocumentException
import com.openbank.document.application.port.out.PartyLookupPort
import com.openbank.document.application.port.out.ProductCatalogPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.SignatureLevel
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Wires templating + e-signature into account onboarding (ADR-0162 D7): consuming `account.created`
 * exercises the version-resolution policy (`RenderDocumentCommand.templateVersion = null` — always
 * the current PUBLISHED version) and product-catalog's `documentTemplateCode` reference end to end.
 *
 * **Idempotent and resumable** under at-least-once Kafka delivery. The operation has two persisted
 * effects — render the document, then open its ceremony — and neither may be duplicated nor half-
 * applied by a replay:
 *  - the document carries an [Document.idempotencyKey] backed by a partial unique index, so a
 *    concurrent double-delivery renders at most one onboarding contract per account (the loser
 *    catches [DuplicateDocumentException] and reuses the winner's document);
 *  - the ceremony is opened only if one does not already exist for the document, and a partial
 *    unique index on the document's active ceremony makes a concurrent open a caught
 *    [DuplicateCeremonyException]. Because step 2 keys on the document (not on step 1 having just
 *    run), a crash *between* render and open-ceremony self-heals on the next delivery: the document
 *    is found, and its missing ceremony is opened.
 */
@ApplicationScoped
class OnboardingDocumentService(
    private val productCatalogPort: ProductCatalogPort,
    private val partyLookupPort: PartyLookupPort,
    private val accountLookupPort: AccountLookupPort,
    private val renderUseCase: DocumentRenderUseCase,
    private val documentQueryUseCase: DocumentQueryUseCase,
    private val ceremonyUseCase: SignatureCeremonyUseCase,
    private val documentRepository: DocumentRepositoryPort,
    private val clock: Clock,
) : OnboardingDocumentUseCase {

    private val log = Logger.getLogger(OnboardingDocumentService::class.java)

    /**
     * Assembles the Handlebars data map both render call sites need — the fix for a document that
     * used to render against `data = emptyMap()` and left a signed legal contract reading
     * "(the "Customer")" with a blank address (RAMCOVA_SMLOUVA's `{{party.name}}` is the one
     * placeholder the template does NOT `{{#if}}`-guard). Every lookup here is fail-open (see the
     * port kdocs) — an unreachable dependency degrades to an omitted clause (the template already
     * guards account/product/address with `{{#if}}`), never to blocking the signature. `party.name`
     * is the one exception in practice: party-service is structurally guaranteed to have a
     * non-blank legalName (a required domain field), so only a live outage — not "the customer has
     * no name" — would leave it blank, same fail-open trade-off the rest of the fleet accepts for
     * reference-data dependencies.
     *
     * [productIdOverride] lets the eager path (which already knows the exact product from
     * `account.created`) skip re-deriving it from the looked-up account.
     *
     * [partyRef] is typed as a bare `String` everywhere upstream, not a `UUID` — an unparseable
     * value (malformed data, a non-UUID synthetic reference) is treated the same as an unreachable
     * lookup: skip enrichment, never throw. A legal-document render must not fail over a party-id
     * shape issue any more than over a network blip.
     */
    private suspend fun buildAgreementData(
        partyRef: String,
        caseRef: String,
        productIdOverride: UUID? = null,
    ): Map<String, Any?> {
        val partyId = runCatching { UUID.fromString(partyRef) }.getOrNull()
        val party = partyId?.let { partyLookupPort.findById(it) }
        val account = partyId?.let { accountLookupPort.findCurrentAccount(it) }
        val product = (productIdOverride ?: account?.productId)?.let { productCatalogPort.findProduct(it) }
        return mapOf(
            "party" to mapOf("name" to (party?.legalName ?: ""), "address" to party?.formattedAddress),
            "account" to mapOf("iban" to account?.iban),
            "product" to mapOf("name" to product?.name, "code" to product?.code),
            "document" to mapOf("date" to LocalDate.now(clock).toString(), "caseRef" to caseRef),
        )
    }

    override suspend fun issueOnboardingDocument(cmd: IssueOnboardingDocumentCommand) {
        val idempotencyKey = onboardingKey(cmd.accountId)

        // 1. Resolve the onboarding document idempotently: reuse the one already issued for this
        //    account on replay (an O(1) index lookup), otherwise render a new one.
        val document = documentQueryUseCase.findByIdempotencyKey(idempotencyKey)
            ?: renderOnboardingDocument(cmd, idempotencyKey)
            ?: return

        // 2. Ensure exactly one ceremony exists for the document (resumable + race-safe).
        ensureCeremony(cmd, document.id)
    }

    // SwallowedException: the DuplicateDocumentException type itself IS the "a concurrent delivery
    // already rendered this" signal — caught to resolve to the winning document (idempotent), not an
    // error to rethrow.
    @Suppress("SwallowedException")
    private suspend fun renderOnboardingDocument(
        cmd: IssueOnboardingDocumentCommand,
        idempotencyKey: String,
    ): Document? {
        val templateCode = productCatalogPort.findDocumentTemplateCode(cmd.productId)
        if (templateCode == null) {
            log.infof(
                "Product %s has no documentTemplateCode bound — no onboarding document to issue for account %s.",
                cmd.productId,
                cmd.accountId,
            )
            return null
        }
        return try {
            renderUseCase.render(
                RenderDocumentCommand(
                    templateCode = templateCode,
                    templateVersion = null,
                    data = buildAgreementData(
                        partyRef = cmd.partyRef,
                        caseRef = cmd.accountId.toString(),
                        productIdOverride = cmd.productId,
                    ),
                    contentType = "application/pdf",
                    partyRef = cmd.partyRef,
                    caseRef = cmd.accountId.toString(),
                    productRef = cmd.productId.toString(),
                    retainUntil = null,
                    idempotencyKey = idempotencyKey,
                ),
            )
        } catch (e: DuplicateDocumentException) {
            // A concurrent delivery won the insert race — reuse its document (idempotent).
            log.infof(
                "Onboarding document already issued for account %s (concurrent delivery) — reusing.",
                cmd.accountId,
            )
            documentQueryUseCase.findByIdempotencyKey(idempotencyKey)
        }
    }

    // SwallowedException: the DuplicateCeremonyException type itself IS the "a concurrent delivery
    // already opened the ceremony" signal — idempotent no-op, not an error to rethrow.
    @Suppress("SwallowedException")
    private suspend fun ensureCeremony(cmd: IssueOnboardingDocumentCommand, documentId: UUID) {
        if (ceremonyUseCase.findByDocumentId(documentId) != null) return
        try {
            ceremonyUseCase.openCeremony(
                OpenCeremonyCommand(
                    documentId = documentId,
                    signerPartyRefs = listOf(cmd.partyRef),
                    signatureLevel = SignatureLevel.ADVANCED,
                ),
            )
            log.infof("Issued onboarding document %s + ceremony for account %s.", documentId, cmd.accountId)
        } catch (e: DuplicateCeremonyException) {
            // A concurrent replay opened the ceremony first — idempotent, nothing to do.
            log.infof("Onboarding ceremony already open for account %s (concurrent delivery).", cmd.accountId)
        }
    }

    private fun onboardingKey(accountId: UUID) = "$ONBOARDING_KEY_PREFIX$accountId"

    /** Names the party's one LIVE framework agreement — language-independent on purpose, so a
     *  language change supersedes the existing agreement rather than accumulating a second one. */
    private fun agreementKey(partyRef: String) = "$AGREEMENT_KEY_PREFIX$partyRef"

    // ── Customer-driven, language-correct onboarding agreement (ADR-0169 D3) ────────────────────

    // SwallowedException: DuplicateDocumentException IS the "a concurrent tap already rendered this"
    // signal — caught to resolve to the winning agreement (idempotent), not an error to rethrow.
    @Suppress("SwallowedException")
    override suspend fun ensureOnboardingAgreement(partyRef: String, lang: String): OnboardingAgreement {
        val wantCode = frameworkCode(lang)
        val agreementKey = agreementKey(partyRef)
        val agreements = documentQueryUseCase.listByParty(partyRef)
            .filter { it.templateCode.startsWith(FRAMEWORK_BASE) && it.status != DocumentStatus.ARCHIVED }

        // 1. Already signed (any language): onboarding signing is complete — return it untouched.
        //    A signed contract is the immutable legal record; never re-render or supersede it.
        agreements.firstOrNull { it.status == DocumentStatus.SIGNED }?.let { return it.asAgreement(partyRef) }

        // 2. A pending agreement already in the requested language — reuse it (idempotent).
        agreements.firstOrNull { it.templateCode == wantCode }?.let { return it.asAgreement(partyRef) }

        // 3. Any pending agreement in a DIFFERENT language — supersede it: language is still
        //    changeable before signing (ADR-0169 D3). Archiving here is safe — step 1 already
        //    returned for any SIGNED one, so everything left is GENERATED/PENDING_SIGNATURE.
        agreements.forEach { documentRepository.save(it.archive()) }

        // 4. Render fresh in the requested language + open the ceremony. Steps 1-3 are a
        //    check-then-act over a list read, so two concurrent taps on SIGN can both reach here;
        //    the idempotency key makes the DB the arbiter (partial unique index, V6) and the loser
        //    reuses the winner's agreement instead of rendering a second contract. Step 3 released
        //    the key on the rows it archived, so a language change is still free to re-render.
        val caseRef = "$AGREEMENT_KEY_PREFIX$partyRef"
        val document = try {
            renderUseCase.render(
                RenderDocumentCommand(
                    templateCode = wantCode,
                    templateVersion = null,
                    data = buildAgreementData(partyRef = partyRef, caseRef = caseRef),
                    contentType = "application/pdf",
                    partyRef = partyRef,
                    caseRef = caseRef,
                    productRef = null,
                    retainUntil = null,
                    idempotencyKey = agreementKey,
                ),
            )
        } catch (e: DuplicateDocumentException) {
            log.infof("Onboarding agreement already rendered for party %s (concurrent tap) — reusing.", partyRef)
            documentQueryUseCase.findByIdempotencyKey(agreementKey)
                ?: throw IllegalStateException("Duplicate agreement for $partyRef but no document under $agreementKey")
        }
        val ceremony = openOrFindCeremony(document.id, partyRef)
        return OnboardingAgreement(
            ceremonyId = ceremony.id,
            documentId = document.id,
            templateCode = document.templateCode,
            templateVersion = document.templateVersion,
            sha256 = document.sha256,
            documentStatus = document.status,
        )
    }

    private suspend fun Document.asAgreement(partyRef: String): OnboardingAgreement {
        val ceremony = openOrFindCeremony(id, partyRef)
        return OnboardingAgreement(
            ceremonyId = ceremony.id,
            documentId = id,
            templateCode = templateCode,
            templateVersion = templateVersion,
            sha256 = sha256,
            documentStatus = status,
        )
    }

    @Suppress("SwallowedException")
    private suspend fun openOrFindCeremony(documentId: UUID, partyRef: String) =
        ceremonyUseCase.findByDocumentId(documentId) ?: try {
            ceremonyUseCase.openCeremony(
                OpenCeremonyCommand(documentId, listOf(partyRef), SignatureLevel.ADVANCED),
            )
        } catch (e: DuplicateCeremonyException) {
            // A concurrent ensure opened it first — re-read; it is guaranteed present now.
            ceremonyUseCase.findByDocumentId(documentId)
                ?: error("Ceremony for document $documentId vanished after DuplicateCeremonyException")
        }

    /** `cs`/`CS` → `RAMCOVA_SMLOUVA_CS`; unknown languages fall back to the default locale. */
    private fun frameworkCode(lang: String): String {
        val locale = lang.trim().uppercase().takeIf { it in SUPPORTED_LOCALES } ?: DEFAULT_LOCALE
        return "${FRAMEWORK_BASE}_$locale"
    }

    private companion object {
        const val ONBOARDING_KEY_PREFIX = "onboarding:"
        const val AGREEMENT_KEY_PREFIX = "onboarding-agreement:"
        const val FRAMEWORK_BASE = "RAMCOVA_SMLOUVA"
        const val DEFAULT_LOCALE = "CS"
        val SUPPORTED_LOCALES = setOf("CS", "EN")
    }
}
