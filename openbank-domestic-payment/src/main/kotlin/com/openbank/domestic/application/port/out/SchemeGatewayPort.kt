// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DomesticPayment

/**
 * The domestic rail's single exit to "the network" (ADR-0104 D4): build a real ISO 20022 `pacs.008`
 * for the payment, hand it to the scheme (Czech CERTIS / CERTIS-IP), and return the scheme's
 * verdict from the `pacs.002` it answers with. Only implementation today is the in-house
 * `openbank-clearing-simulator`; a real CERTIS adapter slots in behind the same port unchanged.
 */
interface SchemeGatewayPort {
    suspend fun submit(payment: DomesticPayment): SchemeSubmissionOutcome
}

/**
 * The scheme's verdict on a submitted credit transfer, mapped from the `pacs.002` `TxSts`.
 * [accepted] is true for `ACSC`; [reasonCode] carries the `ExternalStatusReason1Code` on reject.
 */
data class SchemeSubmissionOutcome(val accepted: Boolean, val reasonCode: String?)

/**
 * Thrown when the scheme gateway is unreachable; the rail holds the payment in VALIDATED.
 *
 * [requestLeftThisProcess] answers the only question that matters for #4218: may the scheme be
 * holding a live clearing item for this payment? `false` is a POSITIVE claim that it cannot —
 * the connection was refused or the host did not resolve, so no bytes were delivered — and only
 * that claim makes the payment safe to submit again. Every other failure, a timeout above all, is
 * ambiguous and must be treated as "possibly delivered": the default is therefore `true`.
 */
class SchemeGatewayUnavailableException(
    cause: Throwable,
    val requestLeftThisProcess: Boolean = true,
) : RuntimeException("scheme gateway unavailable", cause)
