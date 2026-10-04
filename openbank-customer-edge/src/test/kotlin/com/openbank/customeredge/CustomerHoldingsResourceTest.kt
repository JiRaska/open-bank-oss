// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerHoldingsResource
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Customer routes for declared holdings (#11966). wealth-service trusts its M2M caller completely,
 * so every property asserted here is one the edge alone provides: the owner comes from the token,
 * a by-id route never acts on another party's holding, the valuation source cannot be chosen by
 * the customer, and an upstream body never reaches the app.
 */
class CustomerHoldingsResourceTest {

    private val caller: UUID = UUID.randomUUID()
    private val stranger: UUID = UUID.randomUUID()
    private val holdingId: UUID = UUID.randomUUID()
    private val loanId: UUID = UUID.randomUUID()
    private val svc = "http://wealth.test"
    private val mapper = ObjectMapper()

    private fun resource(upstream: UpstreamClient) =
        CustomerHoldingsResource(upstream, mockk<CustomerPartyResolver> { every { resolve(any()) } returns caller })
            .apply { wealthServiceUrl = svc }

    private fun json(response: Response): JsonNode = mapper.readTree(response.entity as String)

    private fun holding(owner: UUID, status: String = "ACTIVE", source: String = "CUSTOMER_DECLARED") = """
        {"holdingId":"$holdingId","ownerPartyId":"$owner","holdingType":"REAL_ESTATE","isLiability":false,
         "label":"Flat in Brno","amount":6500000.00,"currency":"CZK","valuedAt":"2026-01-15",
         "valuationSource":"$source","appraiserReference":"APR-1","ownershipShare":0.5,
         "attributableAmount":3250000.00,"valuationAgeDays":261,"externalReference":"LV-123",
         "documentIds":[],"status":"$status","pledgedToLoanId":${if (status == "PLEDGED") "\"$loanId\"" else "null"},
         "createdAt":"2026-09-01T10:00:00Z","updatedAt":"2026-09-01T10:00:00Z"}
    """.trimIndent()

    private val validDeclare = """
        {"holdingType":"REAL_ESTATE","label":" Flat in Brno ","ownershipShare":"0.5","externalReference":"LV-123",
         "valuation":{"amount":"6500000","currency":"CZK","valuedAt":"2026-01-15"}}
    """.trimIndent()

    @Test
    fun `declare sends the token's party and forces CUSTOMER_DECLARED whatever the body claims`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val party = slot<String>()
        val sent = slot<String>()
        every { upstream.post(capture(url), capture(party), capture(sent), any()) } returns
            Response.status(201).entity(holding(caller)).build()

        val claimingAppraisal = validDeclare.replace(
            "\"valuedAt\":\"2026-01-15\"}",
            "\"valuedAt\":\"2026-01-15\",\"source\":\"EXPERT_APPRAISAL\",\"appraiserReference\":\"FAKE\"}",
        ).replace("{\"holdingType\"", "{\"ownerPartyId\":\"$stranger\",\"holdingType\"")
        val response = resource(upstream).declare(claimingAppraisal)

        assertThat(response.status).isEqualTo(201)
        assertThat(url.captured).isEqualTo("$svc/api/v1/holdings")
        assertThat(party.captured).isEqualTo(caller.toString())
        val body = mapper.readTree(sent.captured)
        assertThat(body["valuation"]["source"].asText()).isEqualTo("CUSTOMER_DECLARED")
        assertThat(body["valuation"].has("appraiserReference")).isFalse()
        assertThat(body.has("ownerPartyId")).isFalse()
        assertThat(body["label"].asText()).isEqualTo("Flat in Brno")
        assertThat(sent.captured).doesNotContain(stranger.toString()).doesNotContain("FAKE")
    }

    @Test
    fun `the projection drops the owner, the loan id and the appraiser reference`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$svc/api/v1/holdings", caller.toString()) } returns
            Response.ok("[${holding(caller, status = "PLEDGED")}]").build()

        val response = resource(upstream).list()

        assertThat(response.status).isEqualTo(200)
        val item = json(response)[0]
        assertThat(item.has("ownerPartyId")).isFalse()
        assertThat(item.has("pledgedToLoanId")).isFalse()
        assertThat(item.has("appraiserReference")).isFalse()
        assertThat(item["pledged"].asBoolean()).isTrue()
        assertThat(item["valuationSource"].asText()).isEqualTo("CUSTOMER_DECLARED")
        assertThat(item["valuationAgeDays"].asLong()).isEqualTo(261)
        assertThat(item["attributableAmount"].asText()).isEqualTo("3250000")
        assertThat(response.entity as String).doesNotContain(loanId.toString())
    }

    @Test
    fun `list never returns a holding owned by another party`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok("[${holding(stranger)}]").build()

        assertThat(json(resource(upstream).list()).size()).isZero()
    }

    @Test
    fun `invalid input is a 400 and never reaches wealth-service`() {
        val tomorrow = LocalDate.now().plusDays(1)
        val invalid = listOf(
            "not json",
            validDeclare.replace("REAL_ESTATE", "YACHT_FLEET"),
            validDeclare.replace("\" Flat in Brno \"", "\"   \""),
            validDeclare.replace("\"6500000\"", "\"-1\""),
            validDeclare.replace("\"CZK\"", "\"czk\""),
            validDeclare.replace("2026-01-15", tomorrow.toString()),
            validDeclare.replace("\"0.5\"", "\"1.5\""),
            validDeclare.replace("\"0.5\"", "\"0\""),
            validDeclare.replace("\"LV-123\"", "\"${"x".repeat(129)}\""),
        )
        invalid.forEach { body ->
            val upstream = mockk<UpstreamClient>()
            assertThat(resource(upstream).declare(body).status).describedAs(body).isEqualTo(400)
            verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `another party's holding is a 404 on every by-id route and nothing is written`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$svc/api/v1/holdings/$holdingId", caller.toString()) } returns
            Response.ok(holding(stranger)).build()
        val r = resource(upstream)
        val revalueBody = """{"valuation":{"amount":"1","currency":"CZK","valuedAt":"2026-01-15"}}"""

        listOf(
            r.get(holdingId.toString()),
            r.valuations(holdingId.toString()),
            r.revalue(holdingId.toString(), revalueBody),
            r.withdraw(holdingId.toString()),
        ).forEach { response ->
            assertThat(response.status).isEqualTo(404)
            assertThat(response.entity as String).doesNotContain(stranger.toString())
        }
        verify(exactly = 0) { upstream.get("$svc/api/v1/holdings/$holdingId/valuations", any()) }
        verify(exactly = 0) { upstream.put(any(), any(), any()) }
        verify(exactly = 0) { upstream.delete(any(), any()) }
    }

    @Test
    fun `a malformed id is a 404 without any upstream call`() {
        val upstream = mockk<UpstreamClient>()

        assertThat(resource(upstream).get("../../api/v1/holdings").status).isEqualTo(404)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `a non-canonical id goes upstream only in its canonical form`() {
        val canonical = UUID.fromString("1-1-1-1-1")
        val upstream = mockk<UpstreamClient>()
        val urls = mutableListOf<String>()
        every { upstream.get(capture(urls), caller.toString()) } returns
            Response.ok(holding(caller).replace(holdingId.toString(), canonical.toString())).build()
        every { upstream.delete(capture(urls), caller.toString()) } returns Response.ok(holding(caller)).build()

        resource(upstream).withdraw("1-1-1-1-1")

        assertThat(urls).containsExactly("$svc/api/v1/holdings/$canonical", "$svc/api/v1/holdings/$canonical")
    }

    @Test
    fun `revalue forwards only a CUSTOMER_DECLARED valuation for an owned holding`() {
        val upstream = mockk<UpstreamClient>()
        val sent = slot<String>()
        every { upstream.get("$svc/api/v1/holdings/$holdingId", caller.toString()) } returns
            Response.ok(holding(caller, source = "EXPERT_APPRAISAL")).build()
        every { upstream.put("$svc/api/v1/holdings/$holdingId/valuation", caller.toString(), capture(sent)) } returns
            Response.ok(holding(caller)).build()

        val response = resource(upstream).revalue(
            holdingId.toString(),
            """{"valuation":{"amount":"7000000","currency":"CZK","valuedAt":"2026-09-01","source":"MARKET_REFERENCE"}}""",
        )

        assertThat(response.status).isEqualTo(200)
        assertThat(mapper.readTree(sent.captured)["valuation"]["source"].asText()).isEqualTo("CUSTOMER_DECLARED")
    }

    @Test
    fun `withdrawing a pledged holding is HOLDING_LOCKED and never names the loan`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$svc/api/v1/holdings/$holdingId", caller.toString()) } returns
            Response.ok(holding(caller, status = "PLEDGED")).build()
        every { upstream.delete("$svc/api/v1/holdings/$holdingId", caller.toString()) } returns
            Response.status(409)
                .entity("""{"error":"holding is pledged to loan $loanId — lending must release it first"}""")
                .build()

        val response = resource(upstream).withdraw(holdingId.toString())

        assertThat(response.status).isEqualTo(409)
        assertThat(json(response)["code"].asText()).isEqualTo("HOLDING_LOCKED")
        assertThat(response.entity as String).doesNotContain(loanId.toString()).doesNotContain("loan")
    }

    @Test
    fun `valuation history is returned for an owned holding with the source renamed for the app`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$svc/api/v1/holdings/$holdingId", caller.toString()) } returns
            Response.ok(holding(caller)).build()
        every { upstream.get("$svc/api/v1/holdings/$holdingId/valuations", caller.toString()) } returns Response.ok(
            """[{"amount":7000000,"currency":"CZK","valuedAt":"2026-09-01","source":"CUSTOMER_DECLARED",
                 "appraiserReference":null,"recordedAt":"2026-09-02T08:00:00Z"},
                {"amount":6500000,"currency":"CZK","valuedAt":"2026-01-15","source":"EXPERT_APPRAISAL",
                 "appraiserReference":"APR-1","recordedAt":"2026-09-01T10:00:00Z"}]""",
        ).build()

        val body = json(resource(upstream).valuations(holdingId.toString()))

        assertThat(body.size()).isEqualTo(2)
        assertThat(body[1]["valuationSource"].asText()).isEqualTo("EXPERT_APPRAISAL")
        assertThat(body[1].has("appraiserReference")).isFalse()
    }

    @Test
    fun `an upstream failure body is never forwarded`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns
            Response.status(500).entity("""{"error":"SQLState 23505 owner=$stranger"}""").build()

        val response = resource(upstream).list()

        assertThat(response.status).isEqualTo(502)
        assertThat(response.entity as String).doesNotContain("SQLState").doesNotContain(stranger.toString())
    }
}
