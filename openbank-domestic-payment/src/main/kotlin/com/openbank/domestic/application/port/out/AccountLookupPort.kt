// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import java.util.UUID

interface AccountLookupPort {
    /** Returns the partyId that owns [iban], or null if not an internal account. */
    suspend fun findPartyByIban(iban: String): UUID?

    /**
     * Returns the partyId that OWNS [accountId], or null when it cannot be resolved.
     *
     * ADR-0032's documented follow-up. A payment carries only `debtorAccountId`, and the AML
     * adapter filled the case's required `partyId` with it — so every case opened from the payment
     * path pointed at a party that does not exist, and no join from `aml_cases.party_id` to the
     * party register resolved. `party_id` is `not null`, so the column looked populated and
     * healthy the whole time (#3274).
     */
    suspend fun findPartyByAccountId(accountId: UUID): UUID?

    /** Returns the accountId for [iban] (the internal creditor account to credit), or null if it
     *  is not an internal account / cannot be resolved. */
    suspend fun findAccountIdByIban(iban: String): UUID?
}
