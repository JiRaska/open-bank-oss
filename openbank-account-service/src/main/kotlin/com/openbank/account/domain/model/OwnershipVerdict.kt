// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import java.util.UUID

/**
 * The whole answer an ownership verification gives (ADR-0335 D2): does the claimed party own the
 * account, and is it usable, plus ONLY when owned the account's id, which a caller binding a
 * mandate or an order to that account needs (#12387 item 3). Deliberately nothing else — no balance, product, holder or status
 * name — and the SAME answer for an unknown IBAN and for someone else's, so the caller learns
 * nothing about an account it does not already hold the owner of.
 */
data class OwnershipVerdict(val owned: Boolean, val active: Boolean, val accountId: UUID? = null) {
    companion object {
        val NOT_OWNED = OwnershipVerdict(owned = false, active = false)

        /** [account] as seen by a caller claiming it belongs to [claimedPartyId]; null = no such account. */
        fun of(account: Account?, claimedPartyId: UUID): OwnershipVerdict =
            if (account == null || account.partyId != claimedPartyId) {
                NOT_OWNED
            } else {
                OwnershipVerdict(owned = true, active = account.status == AccountStatus.ACTIVE, accountId = account.id)
            }
    }
}
