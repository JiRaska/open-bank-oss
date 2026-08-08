// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.PaymentConfirmationRenderException
import com.openbank.domestic.application.port.out.PaymentConfirmationRenderPort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * Adapter over [DocumentServiceClient] — the synchronous, non-persisting render path for a
 * customer-requested payment confirmation (ADR-0248 #3). Resolves the current PUBLISHED body for
 * `templateCode`, then merges [data] into it via document-service's `preview` endpoint. Neither
 * call writes anything: `listTemplates`/`previewTemplate` are both read-only on the document-service
 * side, so a retry here can never double-render or double-persist anything.
 */
@ApplicationScoped
class PaymentConfirmationRenderAdapter(@RestClient private val client: DocumentServiceClient) :
    PaymentConfirmationRenderPort {

    @Inject
    lateinit var self: PaymentConfirmationRenderAdapter

    private val log = Logger.getLogger(PaymentConfirmationRenderAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun renderConfirmation(templateCode: String, data: Map<String, Any?>): String = try {
        self.renderWithResilience(templateCode, data)
    } catch (ex: PaymentConfirmationRenderException) {
        throw ex
    } catch (ex: Exception) {
        log.warnf(ex, "document-service unavailable while rendering confirmation template=%s", templateCode)
        throw PaymentConfirmationRenderException("document-service unavailable for template $templateCode", ex)
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(RENDER_TIMEOUT_MS)
    open suspend fun renderWithResilience(templateCode: String, data: Map<String, Any?>): String {
        val templates = client.listTemplates(LIST_LIMIT).awaitSuspending()
        val template = templates.firstOrNull { it.code == templateCode && it.status == PUBLISHED_STATUS }
            ?: throw PaymentConfirmationRenderException(
                "No PUBLISHED document-service template for code $templateCode",
            )

        val preview = client.previewTemplate(PreviewTemplateRequest(bodyHtml = template.bodyHtml, data = data))
            .awaitSuspending()
        return preview.renderedHtml
    }

    private companion object {
        const val RENDER_TIMEOUT_MS = 8_000L
        const val LIST_LIMIT = 200
        const val PUBLISHED_STATUS = "PUBLISHED"
    }
}
