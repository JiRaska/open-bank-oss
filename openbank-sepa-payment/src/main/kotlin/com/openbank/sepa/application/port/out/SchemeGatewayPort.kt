// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.out

import com.openbank.sepa.domain.model.SepaPayment

/**
 * The rail's single exit to "the network" (ADR-0104 D3): build a real ISO 20022 `pacs.008` for the
 * payment, hand it to the scheme, and return the scheme's verdict from the `pacs.002` it answers
 * with. The only implementation today submits to the in-house `openbank-clearing-simulator`; the
 * day a licence + scheme membership exist, it is swapped for a real gateway adapter with nothing
 * above this port changing.
 */
interface SchemeGatewayPort {
    suspend fun submit(payment: SepaPayment): SchemeSubmissionOutcome
}

/**
 * The scheme's verdict on a submitted credit transfer, mapped from the `pacs.002` `TxSts`.
 * `ACSC` is definitive acceptance, `RJCT` definitive refusal, and `RCVD`/`ACSP` remain pending.
 * [reasonCode] carries the ISO 20022 `ExternalStatusReason1Code` on a reject.
 */
enum class SchemeSubmissionDecision { ACCEPTED, REJECTED, PENDING }

data class SchemeSubmissionOutcome(val decision: SchemeSubmissionDecision, val reasonCode: String?)

/** Thrown when the scheme gateway is unreachable; the rail fails closed (holds, never releases). */
class SchemeGatewayUnavailableException(cause: Throwable) : RuntimeException("scheme gateway unavailable", cause)
