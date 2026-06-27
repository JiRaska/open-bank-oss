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
 * [accepted] is true for `ACSC` (settled at the scheme); [reasonCode] carries the ISO 20022
 * `ExternalStatusReason1Code` on a reject (e.g. `AC04`, `AM05`, `RR04`, `FF01`).
 */
data class SchemeSubmissionOutcome(val accepted: Boolean, val reasonCode: String?)

/** Thrown when the scheme gateway is unreachable; the rail fails closed (holds, never releases). */
class SchemeGatewayUnavailableException(cause: Throwable) : RuntimeException("scheme gateway unavailable", cause)
