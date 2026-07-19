// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pins [AccountPageBody] against the shape account-service really emits (ADR-0179).
 *
 * This exists because the failure mode is silent. If the field names drift, Jackson does not
 * throw — it leaves `data` empty, `findOpenAccounts` reports no open accounts, and the merge
 * guard flips from fail-closed to fail-OPEN, permitting exactly the merge it exists to refuse.
 * The first version of this client used `items`/`pageInfo`/`iban` and would have done that.
 *
 * The payload is built from the real [CursorPage] type rather than a hand-written JSON string,
 * so a rename in openbank-libs breaks this test instead of silently disarming the guard.
 */
class AccountServiceClientContractTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    private data class AccountResponseLike(
        val id: UUID,
        val accountNumber: String,
        val partyId: UUID,
        val currencyCode: String,
        val status: String,
    )

    @Test
    fun `deserializes a real CursorPage payload, preserving accounts and cursor`() {
        val accountId = UUID.randomUUID()
        val payload = mapper.writeValueAsString(
            CursorPage(
                data = listOf(
                    AccountResponseLike(accountId, "CZ6508000000192000145399", UUID.randomUUID(), "CZK", "ACTIVE"),
                ),
                pagination = PageInfo(limit = 100, hasNextPage = true, nextCursor = "next-page-cursor"),
            ),
        )

        val decoded = mapper.readValue(payload, AccountPageBody::class.java)

        assertThat(decoded.data).hasSize(1)
        assertThat(decoded.data.single().id).isEqualTo(accountId)
        assertThat(decoded.data.single().accountNumber).isEqualTo("CZ6508000000192000145399")
        assertThat(decoded.data.single().status).isEqualTo("ACTIVE")
        assertThat(decoded.pagination?.hasNextPage).isTrue()
        assertThat(decoded.pagination?.nextCursor).isEqualTo("next-page-cursor")
    }

    @Test
    fun `an empty page deserializes without loss`() {
        val payload = mapper.writeValueAsString(
            CursorPage(
                data = emptyList<AccountResponseLike>(),
                pagination = PageInfo(limit = 100, hasNextPage = false),
            ),
        )

        val decoded = mapper.readValue(payload, AccountPageBody::class.java)

        assertThat(decoded.data).isEmpty()
        assertThat(decoded.pagination?.hasNextPage).isFalse()
        assertThat(decoded.pagination?.nextCursor).isNull()
    }

    @Test
    fun `the account status account-service emits for a closed account is the string the filter matches`() {
        // findOpenAccounts filters on STATUS_CLOSED; if account-service ever renames the enum
        // constant, every closed account starts counting as open and the guard over-blocks
        // (loud, safe) — the reverse of the silent field-name failure above.
        val payload = mapper.writeValueAsString(
            CursorPage(
                data = listOf(
                    AccountResponseLike(UUID.randomUUID(), "CZ99", UUID.randomUUID(), "CZK", "CLOSED"),
                ),
                pagination = PageInfo(limit = 100, hasNextPage = false),
            ),
        )

        val decoded = mapper.readValue(payload, AccountPageBody::class.java)

        assertThat(decoded.data.single().status).isEqualTo("CLOSED")
    }
}
