// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.event

import java.time.Instant
import java.util.UUID

/**
 * A payment the admission chain parked and that needs a human (ADR-0374 D3, D13).
 *
 * Written to the outbox in the same transaction as the move to HELD, so a hold without this event
 * cannot exist; aml-service consumes it and opens the one case of the hold, idempotent on
 * [caseKey]. The rail calls no case endpoint for a hold.
 *
 * Rail-neutral on purpose: [eventType] is the same value on every rail and [rail] says which one,
 * so the case intake is one consumer and not one per rail. It carries no name: the debtor is a
 * party id and an account reference already present on this topic, and the evidence holds list
 * entries and scores, never the screened names.
 */
data class PaymentHeldEvent(
    val paymentId: UUID,
    val rail: String,
    /** Idempotency key of the hold's case. A decision counts only when it comes from this case. */
    val caseKey: String,
    /** Which review of the hold this requests, from 1. A later review is a new case with its own key. */
    val review: Int,
    val debtorAccountId: UUID,
    /** The account's owning party when the standing step could read it; aml-service resolves it otherwise. */
    val debtorPartyId: UUID?,
    val customerReference: String,
    /** Why a human is needed: the reason codes of the steps that decided the hold. */
    val reasons: List<String>,
    val steps: List<HeldStep>,
    val heldAt: Instant,
    /** When the hold ends in rejection if nobody decides it. */
    val expiresAt: Instant,
    val occurredAt: Instant,
    val aggregateRevision: Long,
    val eventType: String = EVENT_TYPE,
    val sourceService: String = "domestic-payment",
) {
    /** One control as the chain ran it. [decision] is null when the step contributed none. */
    data class HeldStep(
        val step: String,
        val outcome: String,
        val decision: String?,
        val reason: String?,
        val evidence: Map<String, String>,
    )

    companion object {
        const val EVENT_TYPE = "PAYMENT_HELD"

        /** The outbox `eventType`, which becomes the `ce-type` header. */
        const val OUTBOX_EVENT_TYPE = "domestic.payment.held"
        const val RAIL = "DOMESTIC"
    }
}
