// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.CustomerPensionResource
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
 * Customer pension routes (ADR-0334 S6). pension-fund-service serves holdings by contract id to any
 * M2M caller and pension-service's surrender preview prices whatever value it is handed, so every
 * property asserted here is one only the edge provides.
 */
class CustomerPensionResourceTest {
    private val caller: UUID = UUID.randomUUID()
    private val stranger: UUID = UUID.randomUUID()
    private val contractId: UUID = UUID.randomUUID()
    private val pension = "http://pension.test"
    private val fund = "http://fund.test"
    private val catalog = "http://catalog.test"
    private val sca = "http://sca.test"
    private val challenge: UUID = UUID.randomUUID()
    private val mapper = ObjectMapper()

    private fun resource(upstream: UpstreamClient) =
        CustomerPensionResource(upstream, mockk<CustomerPartyResolver> { every { resolve(null) } returns caller })
            .apply {
                pensionServiceUrl = pension
                fundServiceUrl = fund
                catalogUrl = catalog
                scaServiceUrl = sca
            }

    private fun json(response: Response): JsonNode = mapper.readTree(response.entity as String)

    private fun ok(body: String, status: Int = 200) = Response.status(status).entity(body).build()

    private fun contract(owner: UUID, status: String = "ACTIVE") = """
        {"contractId":"$contractId","participantPartyId":"$owner","productLine":"DPS","jurisdiction":"CZ",
         "packVersion":1,"providerEntityId":"${UUID.randomUUID()}","providerType":"PENSION_COMPANY",
         "status":"$status","schedule":{"amount":1000,"currency":"CZK","frequency":"MONTHLY"},
         "currentStrategy":{"strategyCode":"BALANCED","effectiveFrom":"2026-01-01","electedAt":"2026-01-01T10:00:00Z"},
         "strategyHistory":[],"beneficiaries":[],"startDate":"2026-01-01",
         "createdAt":"2026-01-01T10:00:00Z","updatedAt":"2026-01-01T10:00:00Z"}
    """.trimIndent()

    private fun holdings(vararg values: String?) = """
        {"contractId":"$contractId","pendingOrders":[],"holdings":[${
        values.joinToString(",") { v ->
            """{"fundId":"${UUID.randomUUID()}","units":10,"navPerUnit":${v?.let { "1.5" } ?: "null"},
               "navDate":"2026-10-01","value":${v ?: "null"},"currency":"CZK"}"""
        }
    }]}
    """.trimIndent()

    private val validCreate = """
        {"productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
         "providerType":"PENSION_COMPANY","birthDate":"1990-05-01","strategyCode":"BALANCED",
         "schedule":{"amount":"1000","currency":"CZK","frequency":"MONTHLY"},
         "participantPartyId":"$stranger","currentValue":"999999"}
    """.trimIndent()

    @Test
    fun `create sends the token's party and drops every field the customer may not choose`() {
        val upstream = mockk<UpstreamClient>()
        val party = slot<String>()
        val sent = slot<String>()
        every {
            upstream.post("$pension/api/v1/pension/contracts", capture(party), capture(sent), any())
        } returns
            ok(contract(caller, "DRAFT"), 201)

        val response = resource(upstream).create(validCreate, "key-1")

        assertThat(response.status).isEqualTo(201)
        assertThat(party.captured).isEqualTo(caller.toString())
        assertThat(sent.captured).doesNotContain(stranger.toString()).doesNotContain("currentValue")
        assertThat(json(response).has("participantPartyId")).isFalse()
        assertThat(json(response)["status"].asText()).isEqualTo("DRAFT")
    }

    @Test
    fun `create refuses beneficiary shares that do not total 100 without calling upstream`() {
        val upstream = mockk<UpstreamClient>()
        val body = validCreate.replace(
            "\"strategyCode\":\"BALANCED\"",
            "\"strategyCode\":\"BALANCED\",\"beneficiaries\":[{\"name\":\"A\",\"sharePercent\":60}]",
        )

        val response = resource(upstream).create(body, null)

        assertThat(response.status).isEqualTo(400)
        assertThat(json(response)["error"].asText()).contains("total 100")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `another party's contract is 404 and the unit register is never read`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(stranger))

        val response = resource(upstream).contract(contractId.toString())

        assertThat(response.status).isEqualTo(404)
        verify(exactly = 0) { upstream.get(match { it.startsWith(fund) }, any()) }
    }

    @Test
    fun `a malformed contract id is 404 without any upstream call`() {
        val upstream = mockk<UpstreamClient>()

        assertThat(resource(upstream).contract("1-1-1-1-1x").status).isEqualTo(404)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `the overview carries holdings valued per currency`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(caller))
        every { upstream.get("$fund/api/v1/contracts/$contractId/holdings") } returns ok(holdings("15", "25.5"))

        val body = json(resource(upstream).contract(contractId.toString()))

        assertThat(body["valuation"]["totals"]["CZK"].asText()).isEqualTo("40.5")
        assertThat(body["valuation"]["complete"].asBoolean()).isTrue()
        assertThat(body.has("participantPartyId")).isFalse()
    }

    @Test
    fun `the surrender preview is priced from the unit register, never from the caller`() {
        val upstream = mockk<UpstreamClient>()
        val sent = slot<String>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(caller))
        every { upstream.get("$fund/api/v1/contracts/$contractId/holdings") } returns ok(holdings("100", "50"))
        every {
            upstream.post(
                "$pension/api/v1/pension/contracts/$contractId/early-termination",
                caller.toString(),
                capture(sent),
                any(),
            )
        } returns
            ok(
                """{"currentValue":150,"fee":0,"estimatedNetPayout":150,"clawbacks":[],"notes":[],"contract":{"status":"ACTIVE"}}""",
            )

        val response = resource(upstream).earlyTerminationPreview(contractId.toString(), null)

        assertThat(response.status).isEqualTo(200)
        val upstreamBody = mapper.readTree(sent.captured)
        assertThat(upstreamBody["currentValue"].decimalValue()).isEqualByComparingTo("150")
        assertThat(upstreamBody["confirm"].asBoolean()).isFalse()
        assertThat(json(response)["incentiveHistoryIncluded"].asBoolean()).isFalse()
    }

    @Test
    fun `a holding with no published NAV refuses the preview instead of understating it`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(caller))
        every { upstream.get("$fund/api/v1/contracts/$contractId/holdings") } returns ok(holdings("100", null))

        val response = resource(upstream).earlyTerminationNotice(contractId.toString(), null, challenge.toString())

        assertThat(response.status).isEqualTo(409)
        assertThat(json(response)["code"].asText()).isEqualTo("VALUATION_INCOMPLETE")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `an upstream rule refusal is a fixed code and never the upstream body`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(caller))
        every { upstream.post(match { it.startsWith(sca) }, any(), any(), any()) } returns
            ok("""{"status":"COMPLETED"}""")
        every { upstream.put(any(), any(), any()) } returns ok("""{"error":"pack CZ/DPS v1 internal detail"}""", 400)

        val response = resource(
            upstream,
        ).strategy(contractId.toString(), """{"strategyCode":"DYNAMIC"}""", challenge.toString())

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity as String).doesNotContain("internal detail")
        assertThat(json(response)["code"].asText()).isEqualTo("PENSION_RULE_REFUSED")
    }

    @Test
    fun `products lists only published DPS and DIP offerings`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$catalog/api/v2/offerings") } returns ok("""[{"id":"o1"},{"id":"o2"}]""")
        every { upstream.get("$catalog/api/v2/products/o1") } returns
            ok("""{"content":{"name":"DPS balanced","attributes":{"productLine":"DPS","riskClass":3}}}""")
        every { upstream.get("$catalog/api/v2/products/o2") } returns
            ok("""{"content":{"name":"Current account","attributes":{"productLine":"CURRENT"}}}""")

        val body = json(resource(upstream).products())

        assertThat(body.size()).isEqualTo(1)
        assertThat(body[0]["offeringId"].asText()).isEqualTo("o1")
    }

    @Test
    fun `a tax year in the future is refused before any call`() {
        val upstream = mockk<UpstreamClient>()

        assertThat(resource(upstream).taxSummary(contractId.toString(), "2999").status).isEqualTo(400)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `a payout quote goes to the S5 exit route and a malformed payout id is 404 without a call`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(caller))
        every { upstream.post(capture(url), caller.toString(), any(), any()) } returns ok("""{"payoutId":"p"}""", 201)

        val quoted = resource(upstream).payout(contractId.toString(), """{"form":"LUMP_SUM"}""", null)
        val confirmed = resource(upstream).confirmPayout(
            contractId.toString(),
            "not-a-uuid",
            """{"payoutIban":"CZ6508000000192000145399"}""",
            null,
            challenge.toString(),
        )

        assertThat(quoted.status).isEqualTo(201)
        assertThat(url.captured).isEqualTo("$pension/api/v1/pension/contracts/$contractId/exit/payouts/quote")
        assertThat(confirmed.status).isEqualTo(404)
        verify(exactly = 1) { upstream.post(any(), any(), any(), any()) }
    }

    private fun ownedContract(upstream: UpstreamClient) {
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(caller))
    }

    @Test
    fun `a state change without SCA is 403 SCA_REQUIRED with the linking to sign, and nothing goes upstream`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)

        val response = resource(upstream).strategy(contractId.toString(), """{"strategyCode":"DYNAMIC"}""", null)

        assertThat(response.status).isEqualTo(403)
        val body = json(response)
        assertThat(body["code"].asText()).isEqualTo("SCA_REQUIRED")
        assertThat(body["scaLinking"]["purpose"].asText()).isEqualTo("APPROVAL")
        assertThat(body["scaLinking"]["approvalRequestId"].asText()).isEqualTo("pension.strategy:$contractId")
        assertThat(body["scaLinking"]["payloadSha256"].asText()).matches("[0-9a-f]{64}")
        verify(exactly = 0) { upstream.put(any(), any(), any()) }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a challenge signed for a different payload is refused by sca-service and the change is not made`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val consumed = mutableListOf<String>()
        // sca-service compares payloadSha256 strictly; the challenge in this test signed CONSERVATIVE.
        val signed = resource(upstream).strategy(contractId.toString(), """{"strategyCode":"CONSERVATIVE"}""", null)
            .let { json(it)["scaLinking"]["payloadSha256"].asText() }
        every {
            upstream.post(
                match {
                    it == "$sca/api/v1/sca/challenges/$challenge/consume"
                },
                caller.toString(),
                capture(consumed),
                any(),
            )
        } answers {
            val sent = mapper.readTree(thirdArg<String>())
            if (sent["payloadSha256"].asText() == signed) ok("{}") else ok("""{"error":"mismatch"}""", 409)
        }

        val response = resource(
            upstream,
        ).strategy(contractId.toString(), """{"strategyCode":"DYNAMIC"}""", challenge.toString())

        assertThat(response.status).isEqualTo(403)
        assertThat(json(response)["code"].asText()).isEqualTo("SCA_REJECTED")
        val sent = mapper.readTree(consumed.single())
        assertThat(sent["partyId"].asText()).isEqualTo(caller.toString())
        assertThat(sent["approvalRequestId"].asText()).isEqualTo("pension.strategy:$contractId")
        assertThat(sent["payloadSha256"].asText()).isNotEqualTo(signed)
        verify(exactly = 0) { upstream.put(any(), any(), any()) }
    }

    @Test
    fun `a challenge signed for exactly this payload is consumed and the change goes upstream`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val signed = resource(upstream).strategy(contractId.toString(), """{"strategyCode":"DYNAMIC"}""", null)
            .let { json(it)["scaLinking"]["payloadSha256"].asText() }
        every {
            upstream.post("$sca/api/v1/sca/challenges/$challenge/consume", caller.toString(), any(), any())
        } answers
            {
                if (mapper.readTree(thirdArg<String>())["payloadSha256"].asText() == signed) ok("{}") else ok("{}", 409)
            }
        every {
            upstream.put("$pension/api/v1/pension/contracts/$contractId/strategy", caller.toString(), any())
        } returns
            ok(contract(caller))

        val response = resource(
            upstream,
        ).strategy(contractId.toString(), """{"strategyCode":"DYNAMIC"}""", challenge.toString())

        assertThat(response.status).isEqualTo(200)
        verify(exactly = 1) { upstream.put(any(), any(), any()) }
    }

    @Test
    fun `pausing, the termination notice and a payout confirmation all refuse a missing challenge`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        every { upstream.get("$fund/api/v1/contracts/$contractId/holdings") } returns ok(holdings("100"))

        val paused = resource(upstream).pause(contractId.toString(), null, null)
        val notice = resource(upstream).earlyTerminationNotice(contractId.toString(), null, null)
        val confirmed = resource(upstream).confirmPayout(
            contractId.toString(),
            UUID.randomUUID().toString(),
            """{"payoutIban":"CZ6508000000192000145399"}""",
            null,
            null,
        )

        assertThat(listOf(paused, notice, confirmed).map { it.status }).containsOnly(403)
        assertThat(listOf(paused, notice, confirmed).map { json(it)["code"].asText() }).containsOnly("SCA_REQUIRED")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }
}
