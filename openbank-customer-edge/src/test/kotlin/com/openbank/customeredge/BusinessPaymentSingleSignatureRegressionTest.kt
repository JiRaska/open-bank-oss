// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.application.port.out.BusinessSigningPort
import com.openbank.customeredge.application.port.out.SigningReply
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.BusinessPaymentApprovals
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * #10281: when the entity's policy needs ONE signature, a business payment must behave exactly as it
 * did before the multi-signature hold existed. Both runs below drive the same four payment routes
 * under `X-Acting-For`; one resource has no hold wired (the pre-change code path), the other has the
 * hold enforcing and answering `required = 1`. Every upstream call — URL, party header, body and
 * idempotency key, in order — and the response must be identical, apart from the one extra
 * evaluate call the hold makes to delegation-service.
 */
class BusinessPaymentSingleSignatureRegressionTest {

    private val human = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val sca = UUID.randomUUID()
    private val base = "http://127.0.0.1:1"

    private data class Call(val verb: String, val url: String, val party: String, val body: String?, val key: String?)

    private fun upstream(calls: MutableList<Call>): UpstreamClient = mockk<UpstreamClient>(relaxed = true).also { u ->
        every { u.get(any(), any()) } answers {
            val url = firstArg<String>()
            calls += Call("GET", url, secondArg(), null, null)
            when {
                url.endsWith("/acting-for") ->
                    Response.ok("""[{"partyId":"$company","partyType":"COMPANY","status":"ACTIVE"}]""").build()
                url.endsWith("/accounts/$account") ->
                    Response.ok(
                        """{"id":"$account","partyId":"$company","accountNumber":"CZ6508000000192000145399"}""",
                    ).build()
                url.endsWith("/parties/$company") -> Response.ok("""{"legalName":"Firma s.r.o."}""").build()
                else -> Response.status(404).build()
            }
        }
        every { u.post(any(), any(), any(), any()) } answers {
            val url = firstArg<String>()
            calls += Call("POST", url, secondArg(), thirdArg(), arg(3))
            when {
                url.endsWith("/consume") -> Response.ok("""{"status":"COMPLETED"}""").build()
                else -> Response.status(201).entity("""{"id":"pay-1","status":"RECEIVED"}""").build()
            }
        }
    }

    private fun resource(upstream: UpstreamClient, withHold: Boolean) = CustomerEdgeResource(
        upstream,
        mockk<EdgeAuditPublisher>(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        actingForResolver = ActingForResolver(upstream, ObjectMapper(), Clock.systemUTC(), base, true)
        requestHeaders = mockk { every { getHeaderString("X-Acting-For") } returns company.toString() }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns human.toString()
            every { subject } returns human.toString()
        }
        objectMapper = ObjectMapper()
        partyServiceUrl = base
        scaServiceUrl = base
        accountServiceUrl = base
        domesticPaymentServiceUrl = base
        sepaPaymentServiceUrl = base
        sepaInstantServiceUrl = base
        swiftServiceUrl = base
        bankBic = "OPENCZPPXXX"
        if (withHold) {
            val signing = mockk<BusinessSigningPort> {
                every { evaluate(company, any()) } returns SigningReply(200, """{"required":1,"trusted":true}""")
            }
            businessApprovals =
                BusinessPaymentApprovals(signing, upstream, ObjectMapper(), mockk(relaxed = true)).also {
                    it.enforce = true
                }
        }
    }

    private fun drive(withHold: Boolean): Pair<List<Call>, List<Pair<Int, Any?>>> {
        val calls = mutableListOf<Call>()
        val r = resource(upstream(calls), withHold)
        val sepa = """{"debtorAccountId":"$account","amount":"80000","currency":"EUR",""" +
            """"creditorIban":"DE89370400440532013000","creditorName":"Dodavatel","reference":"INV-7"}"""
        val swift = sepa.dropLast(1) + ""","bic":"COBADEFFXXX"}"""
        val domestic = """{"debtorAccountId":"$account","amount":"80000","currency":"CZK",""" +
            """"creditorAccountNumber":"123456789/0800","creditorName":"Dodavatel"}"""
        val responses = listOf(
            r.createDomesticPayment(domestic, "idem-0", sca.toString()),
            r.createSepaPayment(sepa, "idem-1", sca.toString()),
            r.createSepaInstant(sepa, "idem-2", sca.toString()),
            r.createSwift(swift, "idem-3", sca.toString()),
        ).map { it.status to it.entity }
        return calls to responses
    }

    @Test
    fun `required == 1 sends exactly the upstream calls and answers exactly what the pre-hold code did`() {
        val (before, beforeResponses) = drive(withHold = false)
        val (after, afterResponses) = drive(withHold = true)

        assertThat(afterResponses).isEqualTo(beforeResponses)
        assertThat(afterResponses.map { it.first }).containsOnly(201)
        assertThat(after).isEqualTo(before)
        assertThat(before.count { it.url.endsWith("/consume") }).isEqualTo(4)
        assertThat(before.map { it.url }).contains(
            "$base/api/v1/domestic-payments",
            "$base/api/v1/sepa-payments",
            "$base/api/v1/sepa-instant",
            "$base/api/v1/swift",
        )
    }

    @Test
    fun `control - the same drive with required == 2 does NOT reach any rail`() {
        val calls = mutableListOf<Call>()
        val u = upstream(calls)
        val r = resource(u, withHold = true)
        val signing = mockk<BusinessSigningPort> {
            every { evaluate(company, any()) } returns SigningReply(200, """{"required":2}""")
            every { createApproval(company, any()) } returns
                SigningReply(201, """{"id":"${UUID.randomUUID()}","required":2}""")
        }
        r.businessApprovals =
            BusinessPaymentApprovals(signing, u, ObjectMapper(), mockk(relaxed = true)).also { it.enforce = true }

        val resp = r.createSepaPayment(
            """{"debtorAccountId":"$account","amount":"80000","currency":"EUR","creditorIban":"DE89370400440532013000","creditorName":"D"}""",
            "idem-1",
            sca.toString(),
        )

        assertThat(resp.status).isEqualTo(202)
        assertThat(calls.map { it.url }).doesNotContain("$base/api/v1/sepa-payments")
    }
}
