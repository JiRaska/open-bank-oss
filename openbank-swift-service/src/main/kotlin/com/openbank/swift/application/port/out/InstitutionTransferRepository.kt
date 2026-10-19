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

    /** Insert or update [transfer] and write [outbox] in the SAME transaction (transactional outbox). */
    suspend fun saveWithOutbox(transfer: InstitutionTransfer, outbox: List<SwiftOutboxMessage>): InstitutionTransfer

    /**
     * Record [rejection] and write [outbox] in one transaction — unless a rejection for the same
     * payment id already exists, in which case nothing is written. True when this call wrote it.
     */
    suspend fun recordRejectionOnce(rejection: InstitutionTransferRejection, outbox: SwiftOutboxMessage): Boolean
}
