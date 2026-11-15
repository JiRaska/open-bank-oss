// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.legalprocess

import java.util.UUID

enum class IntakeRoute { CZ, SK, DE }

/**
 * Produced at the transport boundary after durable storage of original bytes and delivery
 * evidence. The adapter authenticates the transport, derives sourceNamespace from its
 * configured mailbox/connection, and computes the digest from the stored artifact.
 * Route is a dispatch hint. Legal service, issuer competence and subject are still unverified.
 */
data class ObservedLegalDelivery(
    val sourceNamespace: String,
    val deliveryId: String,
    val route: IntakeRoute,
    val evidenceId: UUID,
    val contentSha256: String,
) {
    init {
        require(SOURCE.matches(sourceNamespace)) { "Invalid transport namespace" }
        require(DELIVERY_ID.matches(deliveryId)) { "Invalid delivery ID" }
        require(SHA256.matches(contentSha256)) { "Invalid evidence digest" }
    }

    override fun toString(): String = "ObservedLegalDelivery(route=$route)"

    private companion object {
        val SOURCE = Regex("[a-z0-9][a-z0-9._:-]{0,127}")
        val DELIVERY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

sealed interface LegalDeliveryOutcome {
    /** A durable pending review entry. This receipt is distinct from legal service. */
    data class Received(val receiptId: UUID) : LegalDeliveryOutcome

    data class Duplicate(val receiptId: UUID) : LegalDeliveryOutcome

    /** Reused transport identity with conflicting immutable evidence. Do not ack as received. */
    data object Conflict : LegalDeliveryOutcome
}

fun interface LegalDeliveryInbox {
    fun record(delivery: ObservedLegalDelivery): LegalDeliveryOutcome
}
