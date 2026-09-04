// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.out

import java.util.UUID

interface AccountLookupPort {
    /**
     * Returns the partyId that OWNS [accountId], or null when it cannot be resolved.
     *
     * The SEPA rail's port of the domestic rail's #3274 fix (#8505). A payment carries only
     * `debtorAccountId`, and the AML adapter filled the case's required `partyId` with it — so
     * every case opened from the SEPA payment path pointed at a party that does not exist, and
     * no join from `aml_cases.party_id` to the party register resolved. `party_id` is
     * `not null`, so the column looked populated and healthy the whole time.
     */
    suspend fun findPartyByAccountId(accountId: UUID): UUID?
}
