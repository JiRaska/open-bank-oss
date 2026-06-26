// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.application.port.out

import com.openbank.sepainstant.domain.model.SctInstPayment
import io.smallrye.mutiny.Uni

/**
 * The instant rail's exit to "the network" (ADR-0104 D4): build a real ISO 20022 `pacs.008`,
 * hand it to the scheme, and return the verdict from the `pacs.002` it answers with. The only
 * implementation today submits to the in-house `openbank-clearing-simulator` (the licence
 * swap-point). Reactive (`Uni`) to fit SCT Inst's non-blocking execution chain.
 */
interface SchemeGatewayPort {
    fun submit(payment: SctInstPayment): Uni<SchemeSubmissionOutcome>
}

/**
 * The scheme's verdict, mapped from the `pacs.002` `TxSts`. [accepted] is true for `ACSC`;
 * [reasonCode] carries the ISO 20022 `ExternalStatusReason1Code` on a reject.
 */
data class SchemeSubmissionOutcome(val accepted: Boolean, val reasonCode: String?)

/** Thrown when the scheme gateway is unreachable; the rail fails closed (holds, never settles). */
class SchemeGatewayUnavailableException(cause: Throwable) : RuntimeException("scheme gateway unavailable", cause)
