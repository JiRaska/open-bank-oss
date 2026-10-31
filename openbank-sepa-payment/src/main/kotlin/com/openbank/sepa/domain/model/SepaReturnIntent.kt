// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.model

import java.time.Instant
import java.util.UUID

/** Internal recovery record, not a payment status. The original request survives uncertain replies. */
data class SepaReturnIntent(
    val originalTransactionId: UUID?,
    val firstReceivedAt: Instant,
    val reasonCode: String?,
    val actorId: String,
    val actorType: String,
    val correlationId: String?,
    val unresolvedCause: String = "REVERSAL_UNPROVEN",
    val resolvedAt: Instant? = null,
    val reversalTransactionId: UUID? = null,
) {
    /** Recovery classification/resolution may change; the original return request may not. */
    fun sameRequestAs(other: SepaReturnIntent): Boolean = originalTransactionId == other.originalTransactionId &&
        firstReceivedAt == other.firstReceivedAt &&
        reasonCode == other.reasonCode &&
        actorId == other.actorId &&
        actorType == other.actorType &&
        correlationId == other.correlationId

    fun idempotencyKey(paymentId: UUID): String = "sepa-reversal-$paymentId"
}
