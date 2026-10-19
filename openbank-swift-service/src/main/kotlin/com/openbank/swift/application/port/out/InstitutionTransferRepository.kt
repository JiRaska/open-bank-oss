// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.port.out

import com.openbank.swift.domain.institution.InstitutionTransfer
import java.time.Instant
import java.util.UUID

/** A release refused by validation, recorded once per payment id so its REJECTED is emitted once. */
data class InstitutionTransferRejection(
    val paymentId: UUID,
    val idempotencyKey: String?,
    val reasonCode: String,
    val reason: String,
    val synthetic: Boolean,
    val createdAt: Instant,
)

/** Persistence port for bank-own transfers; every write that announces a transition carries its outbox rows. */
interface InstitutionTransferRepository {
    suspend fun findByIdempotencyKey(key: String): InstitutionTransfer?

    suspend fun findByPaymentId(paymentId: UUID): InstitutionTransfer?

    /**
     * Unknown submissions and SENT transfers last changed before [cutoff], oldest first, at most
     * [limit] — the resolution sweeper's bounded batch.
     */
    suspend fun findDueForResolution(cutoff: java.time.Instant, limit: Int): List<InstitutionTransfer>

    /**
     * Update an existing [transfer] and write [outbox] in the SAME transaction (transactional
     * outbox). Optimistic: throws [ConcurrentTransitionException] when the stored version is not
     * [transfer]'s — another worker (sweeper, redelivery) moved it first, and this write is void.
     */
    suspend fun saveWithOutbox(transfer: InstitutionTransfer, outbox: List<SwiftOutboxMessage>): InstitutionTransfer

    /**
     * Claim the payment for a TRANSFER and insert [transfer] + [outbox] — all in one transaction.
     * False when the claim (payment id or idempotency key) was already taken by either outcome:
     * then nothing at all is written.
     */
    suspend fun insertClaimed(transfer: InstitutionTransfer, outbox: List<SwiftOutboxMessage>): Boolean

    /**
     * Claim the payment for a REJECTION and record [rejection] + [outbox] in one transaction.
     * False when the claim was already taken (by a transfer or an earlier rejection): nothing written.
     */
    suspend fun recordRejectionOnce(rejection: InstitutionTransferRejection, outbox: SwiftOutboxMessage): Boolean

    /** Take the one-time resend marker under [transfer]'s version; false if anyone else holds/held it. */
    suspend fun takeResendMarker(transfer: InstitutionTransfer): Boolean
}

/** Another worker advanced the transfer first; the losing write was not applied. */
class ConcurrentTransitionException(message: String) : IllegalStateException(message)
