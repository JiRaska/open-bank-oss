// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import java.util.UUID

/**
 * Outbound port (ADR-0002) for the ADR-0220 D3.5 fraud-hold signal (issue #2749): fraud-service
 * only ever sees `accountId`, so raising a party-scoped hold needs one cross-service lookup.
 * Implemented by [com.openbank.fraud.infrastructure.client.AccountServiceClient].
 */
interface AccountPartyLookupPort {
    /** The party owning [accountId], or `null` if it cannot be determined right now. */
    suspend fun findPartyByAccountId(accountId: UUID): UUID?
}
