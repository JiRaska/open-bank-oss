// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
package com.openbank.ledger.domain.model

import com.openbank.libs.domain.money.CurrencyCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GlAccountTest {

    @Test
    fun `creates asset account`() {
        val account = GlAccount(
            id = UUID.randomUUID(),
            code = "1001",
            name = "Nostro Accounts",
            type = GlAccountType.ASSET,
            currency = CurrencyCode.CZK,
            parentId = null,
            isLeaf = true,
            isEnabled = true,
            createdAt = Instant.now(),
        )

        assertThat(account.type).isEqualTo(GlAccountType.ASSET)
        assertThat(account.currency).isEqualTo(CurrencyCode.CZK)
        assertThat(account.isLeaf).isTrue()
        assertThat(account.isEnabled).isTrue()
    }

    @Test
    fun `creates hierarchical account with parent`() {
        val parentId = UUID.randomUUID()
        val child = GlAccount(
            id = UUID.randomUUID(),
            code = "2001",
            name = "Current Account Deposits",
            type = GlAccountType.LIABILITY,
            currency = CurrencyCode.CZK,
            parentId = parentId,
            isLeaf = true,
            isEnabled = true,
            createdAt = Instant.now(),
        )

        assertThat(child.parentId).isEqualTo(parentId)
    }

    @Test
    fun `all account types are available`() {
        val types = GlAccountType.entries
        assertThat(types).containsExactlyInAnyOrder(
            GlAccountType.ASSET,
            GlAccountType.LIABILITY,
            GlAccountType.EQUITY,
            GlAccountType.INCOME,
            GlAccountType.EXPENSE,
        )
    }

    @Test
    fun `disabled account preserves state`() {
        val account = GlAccount(
            id = UUID.randomUUID(),
            code = "9999",
            name = "Archived",
            type = GlAccountType.EXPENSE,
            currency = CurrencyCode.EUR,
            parentId = null,
            isLeaf = true,
            isEnabled = false,
            createdAt = Instant.now(),
        )

        assertThat(account.isEnabled).isFalse()
    }

    @Test
    fun `deposit-control accounts are flagged, others are not`() {
        // Per-currency customer deposit control (ADR-0039 Phase B): 2100/2101/2102/2103.
        GlAccount.DEPOSIT_CONTROL_CODES.forEach { code ->
            assertThat(account(code).isDepositControl)
                .`as`("code %s should be deposit-control", code).isTrue()
        }
        // Cash-clearing, FX-position and P&L accounts carry no sub-ledger dimension.
        listOf("1100", "1990", "5900", "2200").forEach { code ->
            assertThat(account(code).isDepositControl)
                .`as`("code %s should NOT be deposit-control", code).isFalse()
        }
    }

    private fun account(code: String) = GlAccount(
        id = UUID.randomUUID(),
        code = code,
        name = "Account $code",
        type = GlAccountType.LIABILITY,
        currency = CurrencyCode.CZK,
        parentId = null,
        isLeaf = true,
        isEnabled = true,
        createdAt = Instant.now(),
    )
}
