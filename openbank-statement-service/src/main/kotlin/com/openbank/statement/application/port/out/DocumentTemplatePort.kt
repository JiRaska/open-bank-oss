// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.port.out

import io.smallrye.mutiny.Uni

/** A document rendered by document-service and streamed straight back to the caller — never stored. */
data class RenderedDocument(val contentType: String, val body: String)

/**
 * Renders a PUBLISHED document-service template against a Handlebars data map (ADR-0248) via
 * document-service's non-persisting `/api/v1/documents/templates/preview` endpoint. A new outbound
 * synchronous trust-boundary edge (`rules.yaml: trust_boundary_diff_change`) — statement-service was
 * not previously a caller of document-service.
 */
interface DocumentTemplatePort {
    fun renderTemplate(templateCode: String, data: Map<String, Any?>): Uni<RenderedDocument>
}

/**
 * Raised when document-service cannot produce the requested document — the template isn't found /
 * PUBLISHED, or the call itself failed (timeout, 5xx, network). Degrades only this one endpoint;
 * the rest of statement-service (period close, camt.053/MT940/PDF render) is unaffected.
 */
class DocumentServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
