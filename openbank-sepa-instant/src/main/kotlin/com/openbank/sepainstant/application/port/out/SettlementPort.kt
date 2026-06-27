// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.application.port.out

import com.openbank.sepainstant.domain.model.SctInstPayment
import io.smallrye.mutiny.Uni
import java.util.UUID

/**
 * Books the funds in transaction-service once the scheme returns ACSC (ADR-0108).
 * Reactive ([Uni]) to match sepa-instant's non-blocking execution chain.
 */
interface SettlementPort {
    fun settle(payment: SctInstPayment): Uni<SettlementOutcome>
}

/** Result of a successful settlement call. [transactionId] is the created transaction's UUID. */
data class SettlementOutcome(val settled: Boolean, val transactionId: UUID?)

/** Thrown when transaction-service is unreachable or returns an unexpected error. */
class SettlementUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
