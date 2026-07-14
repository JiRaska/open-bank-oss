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
import com.openbank.document.application.port.out.ProductCatalogPort
import com.openbank.document.domain.model.SignatureLevel
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Wires templating + e-signature into account onboarding (ADR-0162 D7): consuming
 * `account.created` is what actually exercises the version-resolution policy
 * (`RenderDocumentCommand.templateVersion = null` — always the current PUBLISHED version) and
 * product-catalog's `documentTemplateCode` reference end to end, for the first time.
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
        val caseRef = cmd.accountId.toString()
        // Idempotent: at-least-once Kafka delivery / a replayed event must not render (and open a
        // ceremony for) the same account's onboarding contract twice.
        val alreadyIssued = documentQueryUseCase.listByParty(cmd.partyRef).any { it.caseRef == caseRef }
        if (alreadyIssued) {
            log.infof("Onboarding document already issued for account %s — skipping.", cmd.accountId)
            return
        }

        val templateCode = productCatalogPort.findDocumentTemplateCode(cmd.productId)
        if (templateCode == null) {
            log.infof(
                "Product %s has no documentTemplateCode bound — no onboarding document to issue for account %s.",
                cmd.productId,
                cmd.accountId,
            )
            return
        }

        val document = renderUseCase.render(
            RenderDocumentCommand(
                templateCode = templateCode,
                templateVersion = null,
                data = emptyMap(),
                contentType = "application/pdf",
                partyRef = cmd.partyRef,
                caseRef = caseRef,
                productRef = cmd.productId.toString(),
                retainUntil = null,
            ),
        )

        ceremonyUseCase.openCeremony(
            OpenCeremonyCommand(
                documentId = document.id,
                signerPartyRefs = listOf(cmd.partyRef),
                signatureLevel = SignatureLevel.ADVANCED,
            ),
        )
        log.infof(
            "Issued onboarding document %s + ceremony for account %s (template %s)",
            document.id,
            cmd.accountId,
            templateCode,
        )
    }
}
