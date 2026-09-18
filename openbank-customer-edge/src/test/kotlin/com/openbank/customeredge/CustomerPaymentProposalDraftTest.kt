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

class CustomerPaymentProposalDraftTest {
    private val maker = UUID.fromString("00000000-0000-0000-0000-000000000101")
    private val owner = UUID.fromString("00000000-0000-0000-0000-000000000102")
    private val account = UUID.fromString("00000000-0000-0000-0000-000000000103")
    private val grant = UUID.fromString("00000000-0000-0000-0000-000000000104")
    private val decisionUrl = "http://account/api/v1/accounts/$account/delegation/" +
        "payment-proposal-authorization?partyId=$maker&amount=1500.00&currency=CZK"
    private val body = """{"debtorAccountId":"$account","amount":"1500.00","currency":"CZK",
        "creditorAccountNumber":"9876543210/0100","creditorName":"Supplier"}"""

    private fun edge(upstream: UpstreamClient, actingForOwner: Boolean = false): CustomerEdgeResource =
        CustomerEdgeResource(
            upstream,
            mockk<EdgeAuditPublisher>(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns maker.toString()
                every { subject } returns maker.toString()
            }
            partyMergeResolver = mockk { every { resolve(maker) } returns maker }
            if (actingForOwner) {
                requestHeaders = mockk<HttpHeaders> {
                    every { getHeaderString("X-Acting-For") } returns owner.toString()
                }
                actingForResolver = mockk { every { resolve(maker, owner.toString()) } returns owner }
            }
            objectMapper = ObjectMapper()
            accountServiceUrl = "http://account"
            domesticPaymentServiceUrl = "http://domestic"
            partyServiceUrl = "http://party"
        }

    private fun upstream(decision: Response): UpstreamClient = mockk<UpstreamClient>().also { client ->
        every { client.get(decisionUrl, maker.toString()) } returns decision
        every { client.get("http://account/api/v1/accounts/$account", owner.toString()) } returns
            Response.ok("""{"id":"$account","partyId":"$owner","accountNumber":"CZ6508000000192000145399"}""")
                .build()
        every { client.get("http://party/api/v1/parties/$owner", owner.toString()) } returns
            Response.ok("""{"legalName":"Owner s.r.o."}""").build()
        every {
            client.post("http://domestic/api/v1/domestic-payment-proposals/drafts", maker.toString(), any(), "key-1")
        } returns Response.status(201).entity("""{"status":"DRAFT"}""").build()
    }

    @Test
    fun `company profile still forwards the human maker and never consumes SCA`() {
        val client = upstream(
            Response.ok(
                """{"authorized":true,"outcome":"ALLOWED","delegationId":"$grant", "grantorPartyId":"$owner"}""",
            ).build(),
        )

        val response = edge(client, actingForOwner = true).createDomesticPaymentProposalDraft(body, "key-1")

        assertThat(response.status).isEqualTo(201)
        verify {
            client.post(
                "http://domestic/api/v1/domestic-payment-proposals/drafts",
                maker.toString(),
                match { it.contains("\"debtorName\":\"Owner s.r.o.\"") && it.contains("\"amount\":1500.00") },
                "key-1",
            )
        }
        verify(exactly = 0) { client.post(match { it.contains("/sca/") }, any(), any()) }
        verify(exactly = 0) { client.post("http://domestic/api/v1/domestic-payments", any(), any(), any()) }
    }

    @Test
    fun `company profile history reads are scoped to the human actor`() {
        val client = mockk<UpstreamClient>()
        val draftId = UUID.randomUUID()
        every {
            client.get("http://domestic/api/v1/domestic-payment-proposals/drafts?limit=20", maker.toString())
        } returns Response.ok("""{"items":[]}""").build()
        every {
            client.get("http://domestic/api/v1/domestic-payment-proposals/drafts/$draftId", maker.toString())
        } returns Response.status(404).build()

        val resource = edge(client, actingForOwner = true)
        assertThat(resource.listDomesticPaymentProposalDrafts(null, 20).status).isEqualTo(200)
        assertThat(resource.getDomesticPaymentProposalDraft(draftId).status).isEqualTo(404)
        verify(exactly = 0) { client.get(any(), owner.toString()) }
    }

    @Test
    fun `FOP maker grant works without acting-for profile`() {
        val client = upstream(
            Response.ok(
                """{"authorized":true,"outcome":"ALLOWED","delegationId":"$grant", "grantorPartyId":"$owner"}""",
            ).build(),
        )
        assertThat(edge(client).createDomesticPaymentProposalDraft(body, "key-1").status).isEqualTo(201)
        verify { client.get(decisionUrl, maker.toString()) }
    }

    @Test
    fun `old provider or absent maker grant fails closed before any owner data read or write`() {
        for (decision in listOf(Response.status(404).build(), Response.ok("""{"authorized":false}""").build())) {
            val client = upstream(decision)
            val response = edge(client).createDomesticPaymentProposalDraft(body, "key-1")
            assertThat(response.status).isEqualTo(403)
            verify(exactly = 0) { client.get("http://account/api/v1/accounts/$account", owner.toString()) }
            verify(exactly = 0) { client.post(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `huge exponential amount is rejected before URL expansion or upstream call`() {
        val client = mockk<UpstreamClient>()
        val response = edge(client).createDomesticPaymentProposalDraft(
            body.replace("1500.00", "1E+100000000"),
            "key-1",
        )

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { client.get(any(), any()) }
    }
}
