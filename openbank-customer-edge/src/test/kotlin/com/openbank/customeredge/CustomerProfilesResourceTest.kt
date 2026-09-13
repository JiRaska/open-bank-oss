// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.CustomerProfilesResource
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class CustomerProfilesResourceTest {

    private val human = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val partyBase = "http://party-service.party.svc:8111"
    private val accountBase = "http://account-service.accounts.svc:8100"

    @Test
    fun `a business-only customer sees a personal profile with no products plus one business profile per mandate`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$partyBase/api/v1/parties/$human", human.toString()) } returns
            Response.ok(
                """{"id":"$human","legalName":"Jana Nováková","status":"ACTIVE","kycStatus":"APPROVED"}""",
            ).build()
        every { upstream.get("$accountBase/api/v1/accounts?partyId=$human", human.toString()) } returns
            Response.ok("""{"data":[]}""").build()
        every { upstream.get("$partyBase/api/v1/parties/$human/acting-for", human.toString()) } returns Response.ok(
            """[{"partyId":"$company","partyType":"COMPANY","legalName":"Příklad s.r.o.","tradingName":"Příklad","status":"ACTIVE","kycStatus":"APPROVED","registrationNumber":"45274649","registrationCountry":"CZ","legalForm":"112","mandate":{"role":"LEGAL_REPRESENTATIVE","authority":"JOINT"}}]""",
        ).build()
        val merge = mockk<PartyMergeResolver> { every { resolve(any()) } answers { firstArg() } }
        val resource = CustomerProfilesResource(
            upstream,
            ActingForResolver(upstream, ObjectMapper(), Clock.systemUTC(), partyBase, true),
            merge,
        ).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns human.toString()
                every { subject } returns human.toString()
            }
            objectMapper = ObjectMapper()
            partyServiceUrl = partyBase
            accountServiceUrl = accountBase
        }

        @Suppress("UNCHECKED_CAST")
        val profiles = (resource.profiles().entity as Map<String, Any?>)["profiles"] as List<Map<String, Any?>>

        assertThat(profiles).hasSize(2)
        assertThat(profiles[0]["kind"]).isEqualTo("PERSONAL")
        assertThat(profiles[0]["partyId"]).isEqualTo(human)
        assertThat(profiles[0]["hasProducts"]).isEqualTo(false)
        assertThat(profiles[1]["kind"]).isEqualTo("BUSINESS")
        assertThat(profiles[1]["partyId"]).isEqualTo(company)
        assertThat(profiles[1]["displayName"]).isEqualTo("Příklad")
        assertThat(profiles[1]["role"]).isEqualTo("LEGAL_REPRESENTATIVE")
        assertThat(profiles[1]["registrationNumber"]).isEqualTo("45274649")
    }
}
