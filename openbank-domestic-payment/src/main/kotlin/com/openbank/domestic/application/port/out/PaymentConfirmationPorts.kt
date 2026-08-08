// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

/**
 * Outbound port for rendering a payment confirmation document (ADR-0248 #3) via
 * `openbank-document-service`'s non-persisting `POST /api/v1/documents/templates/preview` endpoint.
 * Synchronous, on customer request only — never pre-generated, never cached, never persisted a
 * second time here or in document-service.
 */
interface PaymentConfirmationRenderPort {

    /**
     * Render [templateCode] with [data] and return the resulting HTML. Throws
     * [PaymentConfirmationRenderException] if document-service is unreachable or answers an
     * unexpected status — the caller (the confirmation download endpoint) has no fallback: a
     * failed render is a failed download, retryable by the customer, and never blocks anything
     * upstream (ADR-0248 Negative consequences).
     */
    suspend fun renderConfirmation(templateCode: String, data: Map<String, Any?>): String
}

class PaymentConfirmationRenderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
