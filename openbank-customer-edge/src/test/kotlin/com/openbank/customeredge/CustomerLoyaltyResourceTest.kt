// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerLoyaltyResource
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Lístky routes. The two properties that matter: every upstream path is scoped to the RESOLVED
 * party (never something the client sent), and every response is a whitelist projection of the
 * operator-facing shape.
 */
class CustomerLoyaltyResourceTest {

    private val caller: UUID = UUID.randomUUID()
    private val svc = "http://loyalty-service.loyalty.svc:8157"
    private val mapper = ObjectMapper()

    private fun resource(upstream: UpstreamClient) =
        CustomerLoyaltyResource(upstream, mockk<CustomerPartyResolver> { every { resolve(any()) } returns caller })
            .apply { loyaltyServiceUrl = svc }

    private fun json(response: Response): JsonNode = mapper.readTree(response.entity as String)

    private fun JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    @Test
    fun `summary is scoped to the resolved party and drops operator-only fields`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val party = slot<String>()
        every { upstream.get(capture(url), capture(party)) } returns Response.ok(
            """{"partyId":"$caller","balance":650,"earnedThisYear":900,"earnedTotal":1200,
               "nextExpiry":"2027-01-01T00:00:00Z","history":[{"id":"${UUID.randomUUID()}","type":"EARN",
               "leaves":300,"remainingLeaves":300,"earnSourceId":"LOGIN_STREAK","benefitId":null,
               "ruleVersion":"v1","occurredAt":"2026-09-01T00:00:00Z","expiresAt":"2027-01-01T00:00:00Z"}]}""",
        ).build()

        val response = resource(upstream).summary()

        assertThat(url.captured).isEqualTo("$svc/api/v1/loyalty/parties/$caller")
        assertThat(party.captured).isEqualTo(caller.toString())
        assertThat(response.status).isEqualTo(200)
        val body = json(response)
        assertThat(body.keys()).containsExactlyInAnyOrder("balance", "earnedThisYear", "nextExpiry", "history")
        assertThat(body["balance"].asInt()).isEqualTo(650)
        assertThat(body["history"][0].keys()).containsExactlyInAnyOrder(
            "id",
            "type",
            "leaves",
            "earnSourceId",
            "benefitId",
            "occurredAt",
            "expiresAt",
        )
        assertThat(response.entity as String).doesNotContain("ruleVersion", "remainingLeaves", "partyId")
    }

    @Test
    fun `an unavailable loyalty service is a 502 without its body`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.status(503).entity("""{"secret":"x"}""").build()

        val response = resource(upstream).summary()

        assertThat(response.status).isEqualTo(502)
        assertThat(response.entity as String).doesNotContain("secret")
    }

    @Test
    fun `benefits and earn sources project the catalogue`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$svc/api/v1/loyalty/benefits", any()) } returns Response.ok(
            """[{"id":"FX_REFERENCE_RATE_ONE_CONVERSION","engine":"FX_REFERENCE_RATE","priceLeaves":450,
               "validityDays":30,"description":"One conversion","internal":"x"}]""",
        ).build()
        every { upstream.get("$svc/api/v1/loyalty/earn-sources", any()) } returns Response.ok(
            """[{"id":"LOGIN_STREAK","leaves":20,"validityDays":365}]""",
        ).build()

        val benefits = json(resource(upstream).benefits())
        val sources = json(resource(upstream).earnSources())

        assertThat(benefits[0].keys()).containsExactlyInAnyOrder(
            "id",
            "engine",
            "priceLeaves",
            "validityDays",
            "description",
        )
        assertThat(sources[0].keys()).containsExactlyInAnyOrder("id", "leaves", "validityDays")
        assertThat(sources[0]["leaves"].asInt()).isEqualTo(20)
    }

    @Test
    fun `a redemption without an Idempotency-Key never reaches loyalty`() {
        val upstream = mockk<UpstreamClient>()

        val response = resource(upstream).redeem("""{"benefitId":"SAVINGS_RATE_BONUS_90D"}""", null)

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a granted redemption forwards the key unchanged and answers 200`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val body = slot<String>()
        val key = slot<String>()
        val grantId = UUID.randomUUID()
        every { upstream.post(capture(url), any(), capture(body), capture(key)) } returns Response.status(201).entity(
            """{"outcome":"GRANTED","grantId":"$grantId","benefitId":"SAVINGS_RATE_BONUS_90D","grantStatus":"GRANTED"}""",
        ).build()

        val response = resource(upstream).redeem("""{"benefitId":"SAVINGS_RATE_BONUS_90D","partyId":"x"}""", "k-1")

        assertThat(url.captured).isEqualTo("$svc/api/v1/loyalty/parties/$caller/redeem")
        assertThat(key.captured).isEqualTo("k-1")
        assertThat(mapper.readTree(body.captured).keys()).containsExactly("benefitId")
        assertThat(response.status).isEqualTo(200)
        val out = json(response)
        assertThat(out.keys()).containsExactlyInAnyOrder("outcome", "grantId", "benefitId", "grantStatus")
        assertThat(out["grantId"].asText()).isEqualTo(grantId.toString())
    }

    @Test
    fun `a replayed redemption answers ALREADY_GRANTED`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(any(), any(), any(), any()) } returns Response.ok(
            """{"outcome":"ALREADY_GRANTED","grantId":"${UUID.randomUUID()}","benefitId":"B","grantStatus":"GRANTED"}""",
        ).build()

        val response = resource(upstream).redeem("""{"benefitId":"B"}""", "k-1")

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["outcome"].asText()).isEqualTo("ALREADY_GRANTED")
    }

    @Test
    fun `insufficient leaves is a 409 with the shortfall`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(any(), any(), any(), any()) } returns Response.status(409).entity(
            """{"outcome":"INSUFFICIENT_LEAVES","requiredLeaves":800,"availableLeaves":120}""",
        ).build()

        val response = resource(upstream).redeem("""{"benefitId":"SAVINGS_RATE_BONUS_90D"}""", "k-2")

        assertThat(response.status).isEqualTo(409)
        val out = json(response)
        assertThat(out["outcome"].asText()).isEqualTo("INSUFFICIENT_LEAVES")
        assertThat(out["requiredLeaves"].asInt()).isEqualTo(800)
        assertThat(out["availableLeaves"].asInt()).isEqualTo(120)
    }

    @Test
    fun `an unknown benefit is a 404, whether loyalty says so or the id cannot be one`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(any(), any(), any(), any()) } returns
            Response.status(400).entity("""{"error":"unknown benefit"}""").build()

        assertThat(resource(upstream).redeem("""{"benefitId":"NOPE"}""", "k-3").status).isEqualTo(404)
        assertThat(resource(upstream).redeem("""{"benefitId":"../grants"}""", "k-4").status).isEqualTo(404)
        assertThat(resource(upstream).redeem("""{}""", "k-5").status).isEqualTo(400)
        verify(exactly = 1) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `grants project validUntil and nothing operator-only`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok(
            """[{"grantId":"${UUID.randomUUID()}","benefitId":"B","grantStatus":"GRANTED","priceLeaves":300,
               "reservedAt":"2026-09-01T00:00:00Z","grantedAt":"2026-09-01T00:00:00Z","expiresAt":"2026-11-30T00:00:00Z"}]""",
        ).build()

        val out = json(resource(upstream).grants())

        assertThat(url.captured).isEqualTo("$svc/api/v1/loyalty/parties/$caller/grants")
        assertThat(out[0].keys()).containsExactlyInAnyOrder(
            "grantId",
            "benefitId",
            "grantStatus",
            "grantedAt",
            "validUntil",
        )
        assertThat(out[0]["validUntil"].asText()).isEqualTo("2026-11-30T00:00:00Z")
        assertThat(out[0]["grantStatus"].asText()).isEqualTo("GRANTED")
    }
}
