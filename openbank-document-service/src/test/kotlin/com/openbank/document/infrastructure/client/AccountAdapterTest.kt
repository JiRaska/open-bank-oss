// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The CURRENT-account selection is the point of this adapter: SAVINGS is opened alongside CURRENT
 * for every retail onboarding, so "the first account returned" would put a savings IBAN into the
 * framework agreement's Article 2 payment-account clause.
 */
class AccountAdapterTest {

    private val client = mockk<AccountClient>()
    private val adapter = AccountAdapter().also { it.client = client }
    private val partyId: UUID = UUID.randomUUID()
    private val currentProductId: UUID = UUID.randomUUID()

    private fun account(type: String, iban: String, productId: UUID = UUID.randomUUID()) =
        AccountClientResponse(
            id = UUID.randomUUID().toString(),
            accountNumber = iban,
            accountType = type,
            productId = productId.toString(),
            status = "ACTIVE",
        )

    private fun respond(vararg accounts: AccountClientResponse) {
        every { client.listByParty(partyId.toString()) } returns
            Uni.createFrom().item(AccountPageClientResponse(accounts.toList()))
    }

    @Test
    fun `the CURRENT account is chosen even when a SAVINGS account is returned first`(): Unit = runBlocking {
        respond(
            account("SAVINGS", "CZ9999999999"),
            account("CURRENT", "CZ1111111111", currentProductId),
        )

        val info = adapter.findCurrentAccount(partyId)

        assertThat(info?.iban).isEqualTo("CZ1111111111")
        assertThat(info?.productId).isEqualTo(currentProductId)
    }

    @Test
    fun `a party with no CURRENT account yields null rather than the wrong account`(): Unit = runBlocking {
        respond(account("SAVINGS", "CZ9999999999"))

        assertThat(adapter.findCurrentAccount(partyId)).isNull()
    }

    @Test
    fun `an empty account page yields null`(): Unit = runBlocking {
        respond()

        assertThat(adapter.findCurrentAccount(partyId)).isNull()
    }

    @Test
    fun `an unreachable account-service fails OPEN — null, not an exception`(): Unit = runBlocking {
        every { client.listByParty(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThat(adapter.findCurrentAccount(partyId)).isNull()
    }

    @Test
    fun `a malformed productId is caught by the fail-open guard, not propagated`(): Unit = runBlocking {
        respond(
            AccountClientResponse(
                id = UUID.randomUUID().toString(),
                accountNumber = "CZ1111111111",
                accountType = "CURRENT",
                productId = "not-a-uuid",
                status = "ACTIVE",
            ),
        )

        assertThat(adapter.findCurrentAccount(partyId)).isNull()
    }
}
