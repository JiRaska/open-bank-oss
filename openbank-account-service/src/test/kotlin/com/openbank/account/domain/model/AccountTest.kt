// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
package com.openbank.account.domain.model

import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AccountTest {

    private fun account(status: AccountStatus = AccountStatus.ACTIVE) = Account(
        id = UUID.randomUUID(),
        accountNumber = Iban.of("CZ6508000000192000145399"),
        accountType = AccountType.CURRENT,
        partyId = UUID.randomUUID(),
        productId = UUID.randomUUID(),
        currency = CurrencyCode.CZK,
        status = status,
        openedAt = Instant.parse("2026-01-01T00:00:00Z"),
        closedAt = null,
        version = 0L,
    )

    @Test
    fun `active account can debit same currency`() {
        val account = account()

        assertThat(account.canDebit(Money.of(BigDecimal("100.00"), "CZK"))).isTrue()
    }

    @Test
    fun `canDebit rejects currency mismatch`() {
        val account = account()

        assertThatThrownBy { account.canDebit(Money.of(BigDecimal("100.00"), "EUR")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Currency mismatch")
    }

    @Test
    fun `freeze and unfreeze enforce valid transitions`() {
        val active = account(AccountStatus.ACTIVE)
        val frozen = active.freeze()

        assertThat(frozen.status).isEqualTo(AccountStatus.FROZEN)
        assertThat(frozen.unfreeze().status).isEqualTo(AccountStatus.ACTIVE)
        assertThatThrownBy { active.unfreeze() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot unfreeze")
    }

    @Test
    fun `close sets CLOSED status and timestamp`() {
        val fixedClock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
        val closed = account(AccountStatus.DORMANT).close(fixedClock)

        assertThat(closed.status).isEqualTo(AccountStatus.CLOSED)
        assertThat(closed.closedAt).isNotNull()
    }
}
