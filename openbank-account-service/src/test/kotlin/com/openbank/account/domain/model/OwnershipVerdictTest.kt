// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** ADR-0335 D2: the verdict reveals ownership to the owner's claim only, and never another party's status. */
class OwnershipVerdictTest {
    private val owner = UUID.randomUUID()

    @Test
    fun `the owner's active account is owned and active`() {
        val account = account(AccountStatus.ACTIVE)
        assertThat(OwnershipVerdict.of(account, owner))
            .isEqualTo(OwnershipVerdict(owned = true, active = true, accountId = account.id))
    }

    @Test
    fun `the owner's unusable account is owned but not active`() {
        AccountStatus.entries.filter { it != AccountStatus.ACTIVE }.forEach { status ->
            val account = account(status)
            assertThat(OwnershipVerdict.of(account, owner)).`as`(status.name)
                .isEqualTo(OwnershipVerdict(owned = true, active = false, accountId = account.id))
        }
    }

    @Test
    fun `another party's account is indistinguishable from no account, whatever its status`() {
        AccountStatus.entries.forEach { status ->
            assertThat(OwnershipVerdict.of(account(status), UUID.randomUUID())).`as`(status.name)
                .isEqualTo(OwnershipVerdict.NOT_OWNED)
        }
        assertThat(OwnershipVerdict.of(null, owner)).isEqualTo(OwnershipVerdict.NOT_OWNED)
    }

    private fun account(status: AccountStatus) = Account(
        id = UUID.randomUUID(),
        accountNumber = Iban.of("CZ6508000000192000145399"),
        accountType = AccountType.CURRENT,
        partyId = owner,
        productId = UUID.randomUUID(),
        currency = CurrencyCode.of("CZK"),
        status = status,
        openedAt = Instant.now(),
        closedAt = null,
        version = 0,
    )
}
