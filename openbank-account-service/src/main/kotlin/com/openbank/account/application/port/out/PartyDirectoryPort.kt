// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import java.util.UUID

/** A party as the business-account catch-up needs it. `legalName` is the account holder name. */
data class DirectoryParty(val partyId: UUID, val partyType: String, val status: String, val legalName: String)

data class DirectoryPage(val items: List<DirectoryParty>, val hasMore: Boolean)

/**
 * Read-only view of party-service for the business-account catch-up. [listActive] THROWS when
 * party-service cannot be asked: "nothing to catch up" and "could not look" must not read the same.
 */
interface PartyDirectoryPort {
    suspend fun listActive(page: Int, size: Int): DirectoryPage
}
