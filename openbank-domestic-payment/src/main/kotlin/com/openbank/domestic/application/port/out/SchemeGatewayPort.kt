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

/** Thrown when the scheme gateway is unreachable; the rail holds the payment in VALIDATED. */
class SchemeGatewayUnavailableException(cause: Throwable) : RuntimeException("scheme gateway unavailable", cause)
