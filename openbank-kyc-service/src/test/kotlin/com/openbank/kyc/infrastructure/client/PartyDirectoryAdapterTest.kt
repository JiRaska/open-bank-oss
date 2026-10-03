// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The adapter is the only place the orphan-detection denominator can silently shrink: a row it
 * drops is a party that is never reconciled, and `total` keeps its register-wide value regardless.
 */
class PartyDirectoryAdapterTest {

    private val client = mockk<PartyServiceClient>()
    private val adapter = PartyDirectoryAdapter(client)

    private val createdAt = Instant.parse("2026-01-02T03:04:05Z")

    private fun stub(vararg items: PartyListItem, total: Long = items.size.toLong()) {
        every { client.listParties(any(), any()) } returns
            Uni.createFrom().item(PartyListResponse(items = items.toList(), total = total))
    }

    @Test
    fun `maps id status and createdAt for a complete row`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        stub(PartyListItem(id = id, status = "PENDING_KYC", createdAt = createdAt))

        val page = adapter.listParties(0, 100)

        assertThat(page.items).hasSize(1)
        assertThat(page.items.single().id).isEqualTo(id)
        assertThat(page.items.single().status).isEqualTo("PENDING_KYC")
        assertThat(page.items.single().createdAt).isEqualTo(createdAt)
    }

    @Test
    fun `preserves the status verbatim rather than coercing to a kyc-side enum`(): Unit = runBlocking {
        // party-service has already added values its own OpenAPI enum omits (MERGED, ADR-0179);
        // an unknown one must survive the mapping so the detector can treat it as "expected a case".
        stub(PartyListItem(id = UUID.randomUUID(), status = "SOME_FUTURE_STATE", createdAt = createdAt))

        assertThat(adapter.listParties(0, 100).items.single().status).isEqualTo("SOME_FUTURE_STATE")
    }

    @Test
    fun `drops rows missing any of the three reconcilable fields`(): Unit = runBlocking {
        val good = UUID.randomUUID()
        stub(
            PartyListItem(id = null, status = "ACTIVE", createdAt = createdAt),
            PartyListItem(id = UUID.randomUUID(), status = null, createdAt = createdAt),
            PartyListItem(id = UUID.randomUUID(), status = "ACTIVE", createdAt = null),
            PartyListItem(id = good, status = "ACTIVE", createdAt = createdAt),
            total = 4,
        )

        val page = adapter.listParties(3, 50)

        assertThat(page.items.map { it.id }).containsExactly(good)
    }

    @Test
    fun `total stays the register-wide count even when rows are dropped`(): Unit = runBlocking {
        stub(PartyListItem(id = null, status = null, createdAt = null), total = 917)

        val page = adapter.listParties(0, 100)

        assertThat(page.items).isEmpty()
        assertThat(page.total).isEqualTo(917)
    }

    @Test
    fun `an empty page maps to an empty item list`(): Unit = runBlocking {
        stub(total = 0)

        assertThat(adapter.listParties(7, 100).items).isEmpty()
    }

    @Test
    fun `page and size are passed through to the rest client unchanged`(): Unit = runBlocking {
        stub(total = 0)

        adapter.listParties(4, 25)

        verify(exactly = 1) { client.listParties(4, 25) }
    }
}
