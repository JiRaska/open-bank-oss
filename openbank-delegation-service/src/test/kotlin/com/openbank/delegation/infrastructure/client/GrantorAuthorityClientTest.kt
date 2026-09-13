// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GrantorAuthorityClientTest {
    private val rest = mockk<PartyAuthorityRestClient>()
    private val client = RestGrantorAuthorityClient(rest)
    private val principal = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @Test
    fun `individual may act only as self`(): Unit = runBlocking {
        coEvery { rest.getParty(principal) } returns
            AuthorityPartyResponse(principal, "INDIVIDUAL", "ACTIVE", "Alice")

        assertThat(client.authorityFor(principal, principal).verdict)
            .isEqualTo(GrantorAuthorityVerdict.AUTHORIZED)
        assertThat(client.authorityFor(principal, actor).verdict)
            .isEqualTo(GrantorAuthorityVerdict.DENIED)
        coVerify(exactly = 0) { rest.actingFor(any()) }
    }

    @Test
    fun `active organization requires principal in actor mandate set`(): Unit = runBlocking {
        coEvery { rest.getParty(principal) } returns
            AuthorityPartyResponse(principal, "COMPANY", "ACTIVE", "Acme s.r.o.")
        coEvery { rest.actingFor(actor) } returns listOf(ActingForResponse(principal))

        val result = client.authorityFor(principal, actor)

        assertThat(result.verdict).isEqualTo(GrantorAuthorityVerdict.AUTHORIZED)
        assertThat(result.displayName).isEqualTo("Acme s.r.o.")
    }

    @Test
    fun `party service outage is not a denial and still fails closed`(): Unit = runBlocking {
        coEvery { rest.getParty(principal) } throws IllegalStateException("unavailable")

        assertThat(client.authorityFor(principal, actor).verdict)
            .isEqualTo(GrantorAuthorityVerdict.UNVERIFIABLE)
    }
}
