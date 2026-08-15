// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.port.out

/**
 * Hands a rendered document to a delivery channel outside document-service's own object store
 * (ADR-0248): the annual statement of fees is a PAD Art. 5 push duty — the bank must send it, not
 * wait for the customer to request it — so it is rendered via the non-persisting preview path and
 * discarded from this service's perspective once handed off here. No `Document` row, no
 * `document.generated` outbox event; [documentBytes] never touches the object store.
 *
 * [LoggingStatementDeliveryAdapter][com.openbank.document.infrastructure.delivery.LoggingStatementDeliveryAdapter]
 * is the only implementation today — a phase-1 stub, since no real email/postal delivery channel
 * exists anywhere in this repo yet.
 */
interface StatementDeliveryPort {
    fun deliver(partyRef: String, documentBytes: ByteArray, contentType: String, subject: String): DeliveryOutcome
}

/**
 * What a [StatementDeliveryPort.deliver] call actually achieved.
 *
 * A no-op MUST NOT share a value with a real send. The port used to return `Unit`, so the only
 * implementation — a stub that logs and sends nothing — was indistinguishable from a working
 * channel, and [AnnualStatementDeliveryService][com.openbank.document.application.usecase.AnnualStatementDeliveryService]
 * recorded a 400-day "delivered" idempotency key on the strength of it. That key is what a later,
 * real channel would then consult and skip, so the stub did not merely fail to deliver — it
 * suppressed the delivery that would eventually have succeeded.
 *
 * Same rule and same remedy as `PushResult.outcome` (ADR-0252 phase 0): the skipped case gets its
 * own enum value, never a flag shared with success.
 */
enum class DeliveryOutcome {
    /** The document was handed to a real channel that accepted it. Only this may be recorded. */
    DELIVERED,

    /** No channel exists or it is disabled: nothing was sent, and nothing may be recorded. */
    SKIPPED,
}
