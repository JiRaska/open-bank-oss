// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.DocumentPreviewPort
import com.openbank.sepa.application.port.out.DocumentTemplateUnavailableException
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

private const val TEMPLATE_LIST_LIMIT = 200
private const val PREVIEW_TIMEOUT_MS = 10_000L
private const val PUBLISHED_STATUS = "PUBLISHED"

/**
 * Adapter over [DocumentServiceClient] (ADR-0248 #3). Unlike the fraud-scoring shadow path, this
 * one is fail-CLOSED: a payment confirmation the customer explicitly requested must not silently
 * succeed with the wrong (or no) content on a document-service fault. On any failure the download
 * fails and the customer can retry — no payment state is affected either way (ADR-0248's stated
 * acceptable failure mode for the synchronous render-on-click path).
 */
@ApplicationScoped
class DocumentPreviewAdapter(@RestClient private val client: DocumentServiceClient) : DocumentPreviewPort {

    // Self-injection so @Timeout below is applied through the CDI proxy (an in-class call bypasses
    // interceptors) — the same pattern FraudScoringAdapter uses. The outer method catches whatever
    // the interceptor throws (including its own TimeoutException, which is NOT thrown from inside
    // the annotated method's try/catch — it is raised by the interceptor wrapping the call) so a
    // slow document-service always surfaces as the same DocumentTemplateUnavailableException / 502,
    // never a bare 500.
    @Inject
    lateinit var self: DocumentPreviewAdapter

    @Suppress("TooGenericExceptionCaught")
    override suspend fun renderTemplate(templateCode: String, data: Map<String, Any?>): String = try {
        self.renderTemplateWithResilience(templateCode, data)
    } catch (ex: DocumentTemplateUnavailableException) {
        throw ex
    } catch (ex: Exception) {
        throw DocumentTemplateUnavailableException("document-service call failed for code $templateCode", ex)
    }

    @Timeout(value = PREVIEW_TIMEOUT_MS)
    open suspend fun renderTemplateWithResilience(templateCode: String, data: Map<String, Any?>): String {
        val bodyHtml = client.listTemplates(TEMPLATE_LIST_LIMIT).awaitSuspending()
            .firstOrNull { it.code == templateCode && it.status == PUBLISHED_STATUS }?.bodyHtml
            ?: throw DocumentTemplateUnavailableException("No PUBLISHED template found for code $templateCode")

        return client.preview(PreviewTemplateClientRequest(bodyHtml = bodyHtml, data = data)).awaitSuspending()
            .renderedHtml
    }
}
