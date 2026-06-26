// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.application.port.out

import com.openbank.swift.domain.model.SwiftMessage
import java.util.UUID

/** Books the funds for a settled MT103 via transaction-service (ADR-0108). */
interface SettlementPort {
    suspend fun settle(message: SwiftMessage): SettlementOutcome
}

data class SettlementOutcome(val settled: Boolean, val transactionId: UUID?)

/** Thrown when transaction-service is unreachable; the message stays in SENT rather than auto-settling. */
class SettlementUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
