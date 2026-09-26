// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.client

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
 * The adapter's one decision: a 404 is the register's answer ("no such party" → null, refused as
 * not-a-person by the use case), every other failure is the register NOT answering and must
 * propagate so enrolment fails closed as 503 rather than being read as a refusal or an allowance.
 */
class PartyRegisterTypeLookupTest {

    private val client = mockk<PartyRegisterClient>()
    private val lookup = PartyRegisterTypeLookup(client)
    private val id = UUID.randomUUID()

    @Test
    fun `returns the register type`(): Unit = runBlocking {
        every { client.getParty(id) } returns Uni.createFrom().item(PartyTypeView("COMPANY"))
        assertThat(lookup.partyType(id)).isEqualTo("COMPANY")
    }

    @Test
    fun `a 404 is an answer - no such party`(): Unit = runBlocking {
        every { client.getParty(id) } returns Uni.createFrom().failure(WebApplicationException(404))
        assertThat(lookup.partyType(id)).isNull()
    }

    @Test
    fun `any other failure propagates so the caller fails closed`() {
        every { client.getParty(id) } returns Uni.createFrom().failure(WebApplicationException(503))
        assertThatThrownBy { runBlocking { lookup.partyType(id) } }.isInstanceOf(WebApplicationException::class.java)
    }
}
