// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import com.openbank.libs.fincrime.admission.DelegationFactsLookup
import java.util.UUID

/**
 * The grant state this rail re-reads at CREATE time (ADR-0375 D1).
 *
 * **Why a port of its own and not another method on [AccountLookupPort].** That port answers every
 * failure with `null`, which is right for "is this IBAN internal?" and catastrophic here: `null`
 * would mean "this party holds no grant", so an account-service outage would become a confident
 * refusal, and a refusal on the money path reads to the customer exactly like a revoked authority.
 * The return type therefore cannot be nullable — [DelegationFactsLookup] separates "answered, and
 * the answer is nothing" from "not answered".
 */
interface DelegationAuthorityPort {

    /**
     * The grants [partyId] holds over [accountId] right now, or why they could not be read.
     *
     * Implementations MUST NOT collapse a transport failure into an empty list.
     */
    suspend fun factsFor(accountId: UUID, partyId: UUID): DelegationFactsLookup
}
