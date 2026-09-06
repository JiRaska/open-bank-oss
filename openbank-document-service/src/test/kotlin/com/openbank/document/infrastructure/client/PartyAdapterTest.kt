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
 * Two behaviours worth holding: the fail-open stance (an unreachable party-service degrades the
 * contract, it must never fail the ceremony), and the address formatter, which has to skip
 * whichever parts a party record does not have rather than emitting "null" or dangling commas
 * into a legal document.
 */
class PartyAdapterTest {

    private val client = mockk<PartyClient>()
    private val adapter = PartyAdapter().also { it.client = client }
    private val partyId: UUID = UUID.randomUUID()

    private fun respond(address: PartyAddressClientResponse?) {
        every { client.getById(partyId.toString()) } returns
            Uni.createFrom().item(PartyClientResponse(id = partyId.toString(), legalName = "Jan Novák", address = address))
    }

    @Test
    fun `a full address renders as street, postal code city on one line`(): Unit = runBlocking {
        respond(PartyAddressClientResponse(line1 = "Dlouhá 1", city = "Praha", postalCode = "11000"))

        val info = adapter.findById(partyId)

        assertThat(info?.legalName).isEqualTo("Jan Novák")
        assertThat(info?.formattedAddress).isEqualTo("Dlouhá 1, 11000 Praha")
    }

    @Test
    fun `line2 is included between the street and the city line when present`(): Unit = runBlocking {
        respond(PartyAddressClientResponse(line1 = "Dlouhá 1", line2 = "byt 4", city = "Praha", postalCode = "11000"))

        assertThat(adapter.findById(partyId)?.formattedAddress).isEqualTo("Dlouhá 1, byt 4, 11000 Praha")
    }

    @Test
    fun `a missing postal code leaves the city alone, with no leading space`(): Unit = runBlocking {
        respond(PartyAddressClientResponse(line1 = "Dlouhá 1", city = "Praha"))

        assertThat(adapter.findById(partyId)?.formattedAddress).isEqualTo("Dlouhá 1, Praha")
    }

    @Test
    fun `blank parts are skipped rather than producing dangling punctuation`(): Unit = runBlocking {
        respond(PartyAddressClientResponse(line1 = "  ", line2 = "", city = "Praha", postalCode = "11000"))

        assertThat(adapter.findById(partyId)?.formattedAddress).isEqualTo("11000 Praha")
    }

    @Test
    fun `an address record with nothing usable formats to null, not an empty string`(): Unit = runBlocking {
        respond(PartyAddressClientResponse(countryCode = "CZ"))

        assertThat(adapter.findById(partyId)?.formattedAddress).isNull()
    }

    @Test
    fun `a party with no address at all still yields the legal name`(): Unit = runBlocking {
        respond(null)

        val info = adapter.findById(partyId)
        assertThat(info?.legalName).isEqualTo("Jan Novák")
        assertThat(info?.formattedAddress).isNull()
    }

    @Test
    fun `an unreachable party-service fails OPEN — null, not an exception`(): Unit = runBlocking {
        every { client.getById(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThat(adapter.findById(partyId)).isNull()
    }
}
