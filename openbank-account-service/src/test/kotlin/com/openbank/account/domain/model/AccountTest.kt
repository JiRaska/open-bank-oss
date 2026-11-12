// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
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
    fun `only an ACTIVE account is in good standing and every other status says why`() {
        assertThat(account(AccountStatus.ACTIVE).standing)
            .isEqualTo(AccountStanding(mayDebit = true, mayCredit = true, reason = null))
        assertThat(account(AccountStatus.DORMANT).standing)
            .isEqualTo(AccountStanding(mayDebit = false, mayCredit = true, reason = StandingReason.ACCOUNT_DORMANT))
        mapOf(
            AccountStatus.PENDING_ACTIVATION to StandingReason.ACCOUNT_PENDING_ACTIVATION,
            AccountStatus.FROZEN to StandingReason.ACCOUNT_FROZEN,
            AccountStatus.CLOSED to StandingReason.ACCOUNT_CLOSED,
        ).forEach { (status, reason) ->
            assertThat(account(status).standing)
                .isEqualTo(AccountStanding(mayDebit = false, mayCredit = false, reason = reason))
        }
        // The two readings of standing cannot drift: canDebit is the same rule.
        AccountStatus.entries.forEach { status ->
            val account = account(status)
            assertThat(account.canDebit(Money.of(BigDecimal("1.00"), "CZK"))).isEqualTo(account.standing.mayDebit)
        }
    }

    @Test
    fun `a freeze records who placed it and an unfreeze clears it`() {
        val byOperator = account().freeze()
        val byParty = account().freeze(FreezeOrigin.PARTY_STANDING)

        assertThat(byOperator.freezeOrigin).isEqualTo(FreezeOrigin.OPERATOR)
        assertThat(byOperator.isFrozenByPartyStanding()).isFalse()
        assertThat(byParty.freezeOrigin).isEqualTo(FreezeOrigin.PARTY_STANDING)
        assertThat(byParty.isFrozenByPartyStanding()).isTrue()
        assertThat(byParty.unfreeze().freezeOrigin).isNull()
        // A FROZEN row from before the origin was recorded is not a party freeze.
        assertThat(account(AccountStatus.FROZEN).isFrozenByPartyStanding()).isFalse()
    }

    @Test
    fun `an operator freeze takes over a party freeze but nothing else re-freezes a frozen account`() {
        val byParty = account().freeze(FreezeOrigin.PARTY_STANDING)

        val takenOver = byParty.freeze(FreezeOrigin.OPERATOR)
        assertThat(takenOver.status).isEqualTo(AccountStatus.FROZEN)
        assertThat(takenOver.isFrozenByPartyStanding()).isFalse()

        // The party cannot take over an operator freeze, and nobody freezes twice.
        assertThatThrownBy { account().freeze().freeze(FreezeOrigin.PARTY_STANDING) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { account().freeze().freeze() }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { byParty.freeze(FreezeOrigin.PARTY_STANDING) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `close sets CLOSED status and timestamp`() {
        val fixedClock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
        val closed = account(AccountStatus.DORMANT).close(fixedClock)

        assertThat(closed.status).isEqualTo(AccountStatus.CLOSED)
        assertThat(closed.closedAt).isNotNull()
    }
}
