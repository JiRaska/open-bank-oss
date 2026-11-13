// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.domain.institution

/**
 * Payload of `swift.institution-transfer.status.v1` on `openbank.swift.institution-transfers`
 * (contract 9.2, `openbank-contracts/openbank-swift-service`). Serialised verbatim by the outbox;
 * every value is a string or null except [synthetic], so the wire shape does not depend on the
 * ObjectMapper's date or decimal settings. [at] is the RFC 3339 instant of the transition.
 */
data class InstitutionTransferStatusChanged(
    val paymentId: String,
    val idempotencyKey: String?,
    val status: String,
    val messageType: String,
    val schemeReference: String?,
    val reasonCode: String?,
    val reason: String?,
    val at: String,
    val synthetic: Boolean,
    /** Echo of the release's optional `ssiVersion`; null when the release carried none. */
    val ssiVersion: Long? = null,
) {
    companion object {
        const val EVENT_TYPE: String = "swift.institution-transfer.status.v1"
    }
}
