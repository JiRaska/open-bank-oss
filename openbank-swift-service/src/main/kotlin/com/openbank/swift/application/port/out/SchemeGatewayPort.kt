// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.application.port.out

import com.openbank.swift.domain.model.SwiftMessage

/**
 * The SWIFT rail's single exit to "the network" (ADR-0104 D4): convert the MT103 SWIFT message
 * to an ISO 20022 `pacs.008`, hand it to the scheme, and return the `pacs.002` verdict. Only
 * implementation today is the `openbank-clearing-simulator`; a real SWIFT SWIFTNet adapter slots
 * in behind the same port unchanged. Also populates `rawMt` on the message.
 */
interface SchemeGatewayPort {
    suspend fun submit(message: SwiftMessage): SchemeSubmissionOutcome
}

/**
 * The scheme's verdict on a submitted credit transfer, mapped from the `pacs.002` `TxSts`.
 * [accepted] is true for `ACSC`; [reasonCode] carries the `ExternalStatusReason1Code` on reject.
 * [rawMt] is the ISO 20022 pacs.008 XML that was actually submitted on the wire.
 */
data class SchemeSubmissionOutcome(val accepted: Boolean, val reasonCode: String?, val rawMt: String)

/** Thrown when the scheme gateway is unreachable; the rail holds the message in VALIDATED. */
class SchemeGatewayUnavailableException(cause: Throwable) : RuntimeException("scheme gateway unavailable", cause)
