// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.IssueOnboardingDocumentCommand
import com.openbank.document.application.port.`in`.OnboardingDocumentUseCase
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.RenderDocumentCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.application.port.out.DuplicateCeremonyException
import com.openbank.document.application.port.out.DuplicateDocumentException
import com.openbank.document.application.port.out.ProductCatalogPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.SignatureLevel
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
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
    private val renderUseCase: DocumentRenderUseCase,
    private val documentQueryUseCase: DocumentQueryUseCase,
    private val ceremonyUseCase: SignatureCeremonyUseCase,
) : OnboardingDocumentUseCase {

    private val log = Logger.getLogger(OnboardingDocumentService::class.java)

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
                    data = emptyMap(),
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

    private companion object {
        const val ONBOARDING_KEY_PREFIX = "onboarding:"
    }
}
