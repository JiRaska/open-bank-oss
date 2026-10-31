// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.port.out

import com.openbank.swift.domain.model.SwiftMessage
import java.util.UUID

/** Books the funds for a settled MT103 via transaction-service (ADR-0108). */
interface SettlementPort {
    suspend fun settle(message: SwiftMessage): SettlementOutcome
}

/**
 * [permanent] marks an outcome no retry can change (bad stored data, e.g. an unparseable 32A value date):
 * nothing was sent, and the #13686 re-drive must stop rather than spend its attempts on it.
 */
data class SettlementOutcome(val settled: Boolean, val transactionId: UUID?, val permanent: Boolean = false)

/** Thrown when transaction-service is unreachable; the message stays in SENT rather than auto-settling. */
class SettlementUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
