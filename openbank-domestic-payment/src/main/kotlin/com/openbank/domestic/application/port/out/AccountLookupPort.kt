// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import java.util.UUID

interface AccountLookupPort {
    /** Returns the partyId that owns [iban], or null if not an internal account. */
    suspend fun findPartyByIban(iban: String): UUID?

    /** Returns the accountId for [iban] (the internal creditor account to credit), or null if it
     *  is not an internal account / cannot be resolved. */
    suspend fun findAccountIdByIban(iban: String): UUID?
}
