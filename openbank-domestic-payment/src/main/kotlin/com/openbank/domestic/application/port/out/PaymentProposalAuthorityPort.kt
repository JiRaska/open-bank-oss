// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.application.port.out

import java.math.BigDecimal
import java.util.UUID

/** Evidence from account-service, not from a caller-controlled payment body. */
data class PaymentProposalAuthority(val delegationId: UUID, val ownerPartyId: UUID)

interface PaymentProposalAuthorityPort {
    /** Null includes denial, old provider, outage and malformed evidence; every case fails closed. */
    suspend fun authorize(
        accountId: UUID,
        makerPartyId: UUID,
        amount: BigDecimal,
        currency: String,
    ): PaymentProposalAuthority?
}
