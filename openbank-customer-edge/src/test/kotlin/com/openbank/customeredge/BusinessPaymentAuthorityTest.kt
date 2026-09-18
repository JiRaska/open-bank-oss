// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class BusinessPaymentAuthorityTest {
    private val human = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val challenge = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun resource(upstream: UpstreamClient): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk<EdgeAuditPublisher>(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        jwt = mockk {
            every { getClaim<String>("party_id") } returns human.toString()
            every { subject } returns human.toString()
        }
        requestHeaders = mockk<HttpHeaders> {
            every { getHeaderString("X-Acting-For") } returns company.toString()
        }
        partyMergeResolver = mockk { every { resolve(human) } returns human }
        actingForResolver = mockk { every { resolve(human, company.toString()) } returns company }
        objectMapper = ObjectMapper()
        accountServiceUrl = "http://account"
        domesticPaymentServiceUrl = "http://domestic"
        partyServiceUrl = "http://party"
        scaServiceUrl = "http://sca"
        notificationServiceUrl = "http://notification"
        businessDirectPaymentsEnabled = true
    }

    private fun mandateJson(authority: String, signatures: Int): String = """
        [{"partyId":"$company","mandate":{"principalPartyId":"$company",
          "agentPartyId":"$human","status":"ACTIVE","authority":"$authority",
          "requiredSignatures":$signatures}}]
    """.trimIndent()

    private fun upstream(decision: Response): UpstreamClient = mockk<UpstreamClient>().also { upstream ->
        every { upstream.get("http://party/api/v1/parties/$human/acting-for", human.toString()) } returns
            Response.ok(mandateJson("SOLE", 1)).build()
        every { upstream.get("http://account/api/v1/accounts/$account", company.toString()) } returns
            Response.ok("""{"id":"$account","partyId":"$company","accountNumber":"CZ6508000000192000145399"}""")
                .build()
        every {
            upstream.get(
                "http://account/api/v1/accounts/$account/business-payment-authorization?actorPartyId=$human",
                company.toString(),
            )
        } returns decision
        every { upstream.get("http://party/api/v1/parties/$company", any()) } returns
            Response.ok("""{"legalName":"Company"}""").build()
        every { upstream.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns Response.ok().build()
        every { upstream.post("http://sca/api/v1/sca/challenges", any(), any(), any()) } returns
            Response.status(201).build()
        every { upstream.post("http://notification/api/v1/devices", any(), any()) } returns
            Response.status(201).build()
        every { upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any()) } returns
            Response.status(201).entity("""{"id":"${UUID.randomUUID()}","status":"RECEIVED"}""").build()
    }

    private fun body() = """{"debtorAccountId":"$account","amount":"100.00","currency":"CZK",
        "creditorAccountNumber":"123456789/0800","creditorName":"Payee"}"""

    @Test
    fun `company profile creates SCA challenge for the human device`() {
        val upstream = upstream(Response.status(404).build())
        val response = resource(
            upstream,
        ).initiateChallenge(ObjectMapper().readTree("""{"purpose":"PAYMENT"}"""), "sca-1")
        assertThat(response.status).isEqualTo(201)
        verify {
            upstream.post(
                "http://sca/api/v1/sca/challenges",
                human.toString(),
                match { it.contains("\"partyId\":\"$human\"") },
                "sca-1",
            )
        }
    }

    @Test
    fun `push device under company profile remains registered to the human`() {
        val upstream = upstream(Response.status(404).build())
        assertThat(resource(upstream).registerDevice("""{"platform":"FCM","token":"test"}""").status)
            .isEqualTo(201)
        verify {
            upstream.post(
                "http://notification/api/v1/devices",
                human.toString(),
                match { it.contains("\"partyId\":\"$human\"") },
            )
        }
    }

    @Test
    fun `sole company mandate binds SCA to human and debits company account`() {
        val upstream = upstream(
            Response.ok(
                """{"authorized":true,"outcome":"SOLE","ownerPartyId":"$company"}""",
            ).build(),
        )
        val response = resource(upstream).createDomesticPayment(body(), "business-1", challenge)
        assertThat(response.status).isEqualTo(201)
        verify {
            upstream.post(
                "http://sca/api/v1/sca/challenges/$challenge/consume",
                human.toString(),
                match { it.contains("\"partyId\":\"$human\"") },
            )
        }
        verify { upstream.post("http://domestic/api/v1/domestic-payments", company.toString(), any(), "business-1") }
    }

    @Test
    fun `joint mandate cannot submit a unilateral payment or consume SCA`() {
        val upstream = upstream(
            Response.ok(
                """{"authorized":false,"outcome":"APPROVAL_REQUIRED","ownerPartyId":"$company"}""",
            ).build(),
        )
        val response = resource(upstream).createDomesticPayment(body(), "business-2", challenge)
        assertThat(response.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/sca/") }, any(), any()) }
        verify(exactly = 0) { upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any()) }
    }

    @Test
    fun `joint mandate is refused before SCA on all other customer payment rails`() {
        val upstream = upstream(
            Response.ok(
                """{"authorized":false,"outcome":"APPROVAL_REQUIRED","ownerPartyId":"$company"}""",
            ).build(),
        )
        val edge = resource(upstream)
        val body = """{"debtorAccountId":"$account"}"""
        assertThat(edge.createSepaPayment(body, "sepa-1", challenge).status).isEqualTo(403)
        assertThat(edge.createSepaInstant(body, "instant-1", challenge).status).isEqualTo(403)
        assertThat(edge.createSwift(body, "swift-1", challenge).status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/sca/") }, any(), any()) }
    }

    @Test
    fun `missing or owner-mismatched authority fails closed`() {
        for (decision in listOf(
            Response.status(404).build(),
            Response.ok("""{"authorized":true,"outcome":"SOLE","ownerPartyId":"${UUID.randomUUID()}"}""").build(),
        )) {
            val upstream = upstream(decision)
            assertThat(resource(upstream).createDomesticPayment(body(), "business-3", challenge).status).isEqualTo(403)
            verify(exactly = 0) { upstream.post(match { it.contains("/sca/") }, any(), any()) }
        }
    }

    @Test
    fun `live joint mandate overrides a stale sole projection`() {
        val upstream = upstream(
            Response.ok(
                """{"authorized":true,"outcome":"SOLE","ownerPartyId":"$company"}""",
            ).build(),
        )
        every { upstream.get("http://party/api/v1/parties/$human/acting-for", human.toString()) } returns
            Response.ok(mandateJson("JOINT", 2)).build()
        assertThat(resource(upstream).createDomesticPayment(body(), "business-4", challenge).status).isEqualTo(403)
        verify(exactly = 0) {
            upstream.get(match { it.contains("business-payment-authorization") }, any())
        }
    }

    @Test
    fun `rollout switch refuses company debit even when both sources say sole`() {
        val upstream = upstream(
            Response.ok("""{"authorized":true,"outcome":"SOLE","ownerPartyId":"$company"}""").build(),
        )
        val edge = resource(upstream).apply { businessDirectPaymentsEnabled = false }
        assertThat(edge.createDomesticPayment(body(), "business-5", challenge).status).isEqualTo(403)
        verify(exactly = 0) {
            upstream.get(match { it.contains("business-payment-authorization") }, any())
        }
    }
}
