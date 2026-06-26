// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.domestic.application.port.out

import java.util.UUID

interface AccountLookupPort {
    /** Returns the partyId that owns [iban], or null if not an internal account. */
    suspend fun findPartyByIban(iban: String): UUID?
}
