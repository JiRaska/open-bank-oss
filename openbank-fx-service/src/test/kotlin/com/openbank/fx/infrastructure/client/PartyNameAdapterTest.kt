// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.PartyNameUnavailableException
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What the screening gate is handed as "the party's name". Every case where there is no name to
 * hand over must be an exception — a blank, the id, or a placeholder would be screened and CLEAR.
 */
class PartyNameAdapterTest {

    private val client = mockk<PartyServiceClient>()
    private val adapter = PartyNameAdapter(client).also { it.self = it }
    private val partyId: UUID = UUID.randomUUID()

    private fun name() = runBlocking { adapter.screeningName(partyId) }

    @Test
    fun `the legal name is the screened name`() {
        every { client.getParty(partyId.toString()) } returns
            Uni.createFrom().item(PartyNameView(legalName = " Jana Nováková ", tradingName = "JN Design"))

        assertThat(name()).isEqualTo("Jana Nováková")
    }

    @Test
    fun `the trading name is used only when the party carries no legal name`() {
        every { client.getParty(any()) } returns
            Uni.createFrom().item(PartyNameView(legalName = "  ", tradingName = "JN Design"))

        assertThat(name()).isEqualTo("JN Design")
    }

    @Test
    fun `a party with no name at all is unavailable, never an empty name`() {
        every { client.getParty(any()) } returns Uni.createFrom().item(PartyNameView(legalName = null, tradingName = ""))

        assertThatThrownBy { name() }.isInstanceOf(PartyNameUnavailableException::class.java)
    }

    @Test
    fun `a party the bank does not hold is unavailable`() {
        every { client.getParty(any()) } returns Uni.createFrom().failure(WebApplicationException(404))

        assertThatThrownBy { name() }
            .isInstanceOf(PartyNameUnavailableException::class.java)
            .hasMessageContaining(partyId.toString())
    }

    @Test
    fun `an unreachable or failing party-service is unavailable and keeps its cause`() {
        every { client.getParty(any()) } returns Uni.createFrom().failure(WebApplicationException(503))
        assertThatThrownBy { name() }
            .isInstanceOf(PartyNameUnavailableException::class.java)
            .hasCauseInstanceOf(WebApplicationException::class.java)

        every { client.getParty(any()) } throws IllegalStateException("connection refused")
        assertThatThrownBy { name() }.isInstanceOf(PartyNameUnavailableException::class.java)
    }
}
