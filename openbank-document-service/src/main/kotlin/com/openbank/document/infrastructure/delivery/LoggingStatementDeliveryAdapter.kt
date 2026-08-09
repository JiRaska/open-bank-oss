// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.delivery

import com.openbank.document.application.port.out.StatementDeliveryPort
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

// TODO(ADR-0248): replace with a real delivery channel (email/postal) — phase-1 stub only.
//
/**
 * Logs that a delivery WOULD have happened — this never actually sends anything. No email/postal
 * delivery channel exists anywhere in this repo yet (mirrors document-service's own phase-1-stub
 * convention, e.g. `HttpPdfRenderAdapter`'s note on ephemeral seals); wiring a real channel is
 * deliberately out of scope here so ADR-0248's push duty is fulfillable end-to-end (render +
 * hand-off point exist) without pretending a customer has actually received anything.
 *
 * [partyRef] is intentionally NOT interpolated into the log line — it originates from an
 * upstream Kafka event payload, not a value this service controls, and the fleet convention
 * (see `OnboardingDocumentService`) is to never log an untrusted free-form identifier verbatim
 * (CodeQL java/log-injection).
 */
@ApplicationScoped
class LoggingStatementDeliveryAdapter : StatementDeliveryPort {
    private val log = Logger.getLogger(LoggingStatementDeliveryAdapter::class.java)

    override fun deliver(partyRef: String, documentBytes: ByteArray, contentType: String, subject: String) {
        log.infof(
            "STUB DELIVERY (ADR-0248 phase-1, NOT actually sent) — subject=\"%s\" contentType=%s bytes=%d",
            subject,
            contentType,
            documentBytes.size,
        )
    }
}
