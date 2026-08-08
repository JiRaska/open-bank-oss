// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.out

/**
 * Outbound port to `openbank-document-service`'s non-persisting template preview (ADR-0248 #3):
 * renders a PUBLISHED template body against [data] and returns the rendered HTML. Nothing is ever
 * written to document-service's object store through this port — no `Document` row, no
 * `document.generated` outbox event — and sepa-payment never caches or persists the result either.
 */
interface DocumentPreviewPort {
    suspend fun renderTemplate(templateCode: String, data: Map<String, Any?>): String
}

/** The named template has no PUBLISHED version, or document-service could not be reached. */
class DocumentTemplateUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
