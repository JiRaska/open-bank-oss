// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.CustomerBusinessCompanyResource
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class CustomerBusinessCompanyResourceTest {

    private val human = UUID.randomUUID()
    private val colleague = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val partyBase = "http://party"
    private val accountBase = "http://account"
    private val kybBase = "http://kyb"

    private fun upstream(mandateFor: UUID? = company, kyb: Response? = null): UpstreamClient {
        val upstream = mockk<UpstreamClient>()
        val actingFor = mandateFor?.let {
            """[{"partyId":"$it","partyType":"COMPANY","legalName":"Příklad s.r.o.","status":"ACTIVE","mandate":{"role":"LEGAL_REPRESENTATIVE","authority":"JOINT"}}]"""
        } ?: "[]"
        every { upstream.get("$partyBase/api/v1/parties/$human/acting-for", human.toString()) } returns
            Response.ok(actingFor).build()
        every { upstream.get("$partyBase/api/v1/parties/$company", company.toString()) } returns Response.ok(
            """{"id":"$company","partyType":"COMPANY","legalName":"Příklad s.r.o.","status":"ACTIVE",""" +
                """"registrationNumber":"45274649","registrationCountry":"CZ","legalForm":"112",""" +
                """"address":{"line1":"Václavské náměstí 1","line2":null,"city":"Praha","postalCode":"11000","countryCode":"CZ"}}""",
        ).build()
        every { upstream.get("$partyBase/api/v1/parties/$company/mandates", company.toString()) } returns Response.ok(
            """[{"agentPartyId":"$human","role":"LEGAL_REPRESENTATIVE","status":"ACTIVE"},""" +
                """{"agentPartyId":"$colleague","role":"AUTHORISED_SIGNATORY","status":"ACTIVE"},""" +
                """{"agentPartyId":"${UUID.randomUUID()}","role":"LEGAL_REPRESENTATIVE","status":"REVOKED"}]""",
        ).build()
        every { upstream.get("$partyBase/api/v1/parties/$human", company.toString()) } returns
            Response.ok("""{"id":"$human","legalName":"Jana Nováková"}""").build()
        every { upstream.get("$partyBase/api/v1/parties/$colleague", company.toString()) } returns
            Response.ok("""{"id":"$colleague","legalName":"Petr Svoboda"}""").build()
        every { upstream.get("$accountBase/api/v1/accounts?partyId=$company", company.toString()) } returns Response.ok(
            """{"data":[{"id":"$account","accountNumber":"CZ6508000000192000145399","currencyCode":"CZK","accountType":"CURRENT","partyId":"$company"}],"pagination":{"limit":20,"hasNextPage":false,"nextCursor":null}}""",
        ).build()
        val cachedLookup = "$kybBase/api/v1/kyb/lookup/cached?scheme=CZ_ICO&identifier=45274649"
        every { upstream.get(cachedLookup, company.toString()) } returns (
            kyb ?: Response.ok(
                """{"representatives":[{"fullName":"Jana  NOVÁKOVÁ","role":"jednatel"},""" +
                    """{"fullName":"Karel Dvořák","role":"jednatel"}],""" +
                    """"representationRule":{"mode":"JOINT","sourceText":"Jednají dva jednatelé společně."},"fetchedAt":"2026-09-14T08:00:00Z"}""",
            ).build()
            )
        return upstream
    }

    private fun resource(upstream: UpstreamClient) = CustomerBusinessCompanyResource(
        upstream,
        mockk<PartyMergeResolver> { every { resolve(any()) } answers { firstArg() } },
        ActingForResolver(upstream, ObjectMapper(), Clock.systemUTC(), partyBase, true),
    ).apply {
        jwt = mockk {
            every { getClaim<String>("party_id") } returns human.toString()
            every { subject } returns human.toString()
        }
        objectMapper = ObjectMapper()
        partyServiceUrl = partyBase
        accountServiceUrl = accountBase
        kybServiceUrl = kybBase
    }

    @Suppress("UNCHECKED_CAST")
    private fun body(r: Response) = r.entity as Map<String, Any?>

    @Test
    fun `a mandate holder sees the company's register facts, representatives, signing rule and accounts`() {
        val r = resource(upstream()).company(company.toString())

        assertThat(r.status).isEqualTo(200)
        val b = body(r)
        assertThat(b["partyId"]).isEqualTo(company)
        assertThat(b["legalName"]).isEqualTo("Příklad s.r.o.")
        assertThat(b["registrationNumber"]).isEqualTo("45274649")
        assertThat(b["legalForm"]).isEqualTo("112")
        assertThat(b["status"]).isEqualTo("ACTIVE")
        assertThat(b["seat"]).isEqualTo(
            mapOf("street" to "Václavské náměstí 1", "city" to "Praha", "postalCode" to "11000", "country" to "CZ"),
        )
        assertThat(b["signingRule"]).isEqualTo("Jednají dva jednatelé společně.")
        assertThat(b["signingRuleAsOf"]).isEqualTo("2026-09-14T08:00:00Z")
        assertThat(b["representatives"]).isEqualTo(
            listOf(
                mapOf("name" to "Jana Nováková", "role" to "LEGAL_REPRESENTATIVE", "partyId" to human, "isYou" to true),
                mapOf(
                    "name" to "Petr Svoboda",
                    "role" to "AUTHORISED_SIGNATORY",
                    "partyId" to colleague,
                    "isYou" to false,
                ),
                // Register-only member with no party here: real, but not linkable.
                mapOf("name" to "Karel Dvořák", "role" to "jednatel", "partyId" to null, "isYou" to false),
            ),
        )
        assertThat(b["accounts"]).isEqualTo(
            listOf(
                mapOf(
                    "id" to "$account",
                    "iban" to "CZ6508000000192000145399",
                    "currency" to "CZK",
                    "product" to "CURRENT",
                ),
            ),
        )
        assertThat((b["accountsPagination"] as com.fasterxml.jackson.databind.JsonNode).path("hasNextPage").asBoolean())
            .isFalse()
    }

    @Test
    fun `the response carries exactly the fields the published BusinessCompany schema declares`() {
        val schema = ObjectMapper(YAMLFactory())
            .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())
            .path("components").path("schemas").path("BusinessCompany").path("properties")
        val b = body(resource(upstream()).company(company.toString()))

        assertThat(b.keys).isEqualTo(schema.fieldNames().asSequence().toSet())
        @Suppress("UNCHECKED_CAST")
        val rep = (b["representatives"] as List<Map<String, Any?>>).first()
        val repSchema = schema.path("representatives").path("items").path("properties")
        assertThat(rep.keys).isEqualTo(repSchema.fieldNames().asSequence().toSet())
        @Suppress("UNCHECKED_CAST")
        val seat = b["seat"] as Map<String, Any?>
        assertThat(seat.keys).isEqualTo(schema.path("seat").path("properties").fieldNames().asSequence().toSet())
    }

    @Test
    fun `an unavailable register leaves signingRule null instead of failing or inventing one`() {
        val r = resource(upstream(kyb = Response.status(503).entity("{}").build())).company(company.toString())

        assertThat(r.status).isEqualTo(200)
        assertThat(body(r)["signingRule"]).isNull()
        assertThat(body(r)["signingRuleAsOf"]).isNull()
        @Suppress("UNCHECKED_CAST")
        val reps = body(r)["representatives"] as List<Map<String, Any?>>
        assertThat(reps.map { it["partyId"] }).containsExactly(human, colleague)
    }

    @Test
    fun `a company with more than twenty accounts receives a cursor instead of silent truncation`() {
        val upstream = upstream()
        val first = (1..20).joinToString(",") { index ->
            """{"id":"${UUID.randomUUID()}","accountNumber":"CZ$index","currencyCode":"CZK","accountType":"CURRENT"}"""
        }
        every { upstream.get("$accountBase/api/v1/accounts?partyId=$company", company.toString()) } returns Response.ok(
            """{"data":[$first],"pagination":{"limit":20,"hasNextPage":true,"nextCursor":"next+cursor"}}""",
        ).build()
        val nextPage = "$accountBase/api/v1/accounts?partyId=$company&cursor=next%2Bcursor"
        every { upstream.get(nextPage, company.toString()) } returns
            Response.ok(
                """{"data":[{"id":"$account","accountNumber":"CZ21","currencyCode":"CZK","accountType":"CURRENT"}],"pagination":{"limit":20,"hasNextPage":false,"nextCursor":null}}""",
            ).build()

        val resource = resource(upstream)
        val profile = body(resource.company(company.toString()))
        @Suppress("UNCHECKED_CAST")
        assertThat(profile["accounts"] as List<Any>).hasSize(20)
        val pagination = profile["accountsPagination"] as com.fasterxml.jackson.databind.JsonNode
        assertThat(pagination.path("hasNextPage").asBoolean()).isTrue()
        assertThat(pagination.path("nextCursor").asText()).isEqualTo("next+cursor")
        val next = body(resource.companyAccounts(company.toString(), "next+cursor"))
        @Suppress("UNCHECKED_CAST")
        assertThat(next["data"] as List<Any>).hasSize(1)
    }

    @Test
    fun `a failed account read cannot look like an empty account list`() {
        val upstream = upstream()
        every { upstream.get("$accountBase/api/v1/accounts?partyId=$company", company.toString()) } returns
            Response.status(503).build()

        assertThat(resource(upstream).company(company.toString()).status).isEqualTo(502)
    }

    @Test
    fun `no active mandate for the named company is a 403 and nothing about the company is read`() {
        val upstream = upstream(mandateFor = null)

        assertThatThrownBy {
            resource(upstream).company(company.toString())
        }.isInstanceOf(ForbiddenException::class.java)
        verify(exactly = 0) { upstream.get("$partyBase/api/v1/parties/$company", any()) }
        verify(exactly = 0) { upstream.get(match { it.startsWith(accountBase) }, any()) }
    }

    @Test
    fun `without X-Acting-For, or naming the customer themselves, the route is a 403`() {
        val upstream = upstream()

        assertThatThrownBy { resource(upstream).company(null) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { resource(upstream).company(human.toString()) }.isInstanceOf(ForbiddenException::class.java)
        verify(exactly = 0) { upstream.get(match { it.startsWith(accountBase) }, any()) }
    }
}
