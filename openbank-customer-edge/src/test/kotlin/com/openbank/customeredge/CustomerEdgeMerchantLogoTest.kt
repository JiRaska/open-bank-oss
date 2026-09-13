// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * The merchant logo, as a customer's app sees it.
 *
 * Two properties are being held, and the second is easy to lose while the first still looks right.
 *
 * A logo must never be fetched from a third party: an external host would learn the customer's IP
 * address and which merchant they paid, every time a statement renders, and the image would load
 * perfectly while it happened. transaction-service therefore emits an ORIGIN-RELATIVE path.
 *
 * But this edge serves `/customer/v1`, not `/api/v1`, so the service's own path — correct on the
 * service — resolves to nothing here. Forwarding it unchanged, which is what the edge does for every
 * other field of `merchant`, gives an app a URL that 404s. The rewrite is the exception, and these
 * tests are why it may not be dropped as redundant.
 */
class CustomerEdgeMerchantLogoTest {

    private val party = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    private fun resource(upstream: UpstreamClient): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns party.toString()
            every { subject } returns party.toString()
        }
        objectMapper = ObjectMapper()
        accountServiceUrl = "http://account"
        transactionServiceUrl = "http://tx"
    }

    private fun page(logoUrl: String?): String {
        val merchant = if (logoUrl == null) {
            """{"cleanName":"Alza.cz","source":"ENRICHED"}"""
        } else {
            """{"cleanName":"Alza.cz","logoUrl":"$logoUrl","source":"ENRICHED"}"""
        }
        return """{"data":[{"id":"${UUID.randomUUID()}","sourceAccountId":"$accountId",
            "targetAccountId":null,"merchant":$merchant}],"pagination":{"limit":20,"hasNextPage":false}}"""
    }

    private fun listWith(logoUrl: String?): String {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$accountId") }, any()) } returns
            Response.ok("""{"id":"$accountId","partyId":"$party"}""").build()
        every { upstream.get(match { it.contains("/api/v1/transactions") }, any()) } returns
            Response.ok(page(logoUrl)).build()
        val resp = resource(upstream).listTransactions(accountId, 20, null)
        return resp.entity as String
    }

    @Test
    fun `the logo path is rewritten onto this edge, query string intact`() {
        val body = listWith("/api/v1/merchants/ALZACZ/logo?size=64&v=0123456789abcdef")

        assertThat(body).contains("/customer/v1/merchants/ALZACZ/logo?size=64&v=0123456789abcdef")
        assertThat(body).doesNotContain("/api/v1/merchants/")
    }

    /**
     * The property the whole design exists for. If an external URL ever reached this field, the
     * rewrite must not launder it into looking local — it does not match the upstream prefix, so it
     * is left exactly as it is and stays visible as the anomaly it would be.
     */
    @Test
    fun `an absolute third-party URL is not rewritten into looking like ours`() {
        val body = listWith("https://logo.example.com/alza.cz.png")

        assertThat(body).doesNotContain("/customer/v1/merchants/https")
    }

    @Test
    fun `a transaction with no logo is left alone`() {
        val body = listWith(null)

        assertThat(body).contains("Alza.cz")
        assertThat(body).doesNotContain("logoUrl")
    }

    @Test
    fun `the logo route proxies the bytes from transaction-service as an image`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.getRaw(capture(url), any(), any()) } returns
            Response.ok(byteArrayOf(1, 2, 3), "image/png").build()

        val resp = resource(upstream).merchantLogo("ALZACZ", 128)

        assertThat(resp.status).isEqualTo(200)
        assertThat(url.captured).isEqualTo("http://tx/api/v1/merchants/ALZACZ/logo?size=128")
        verify { upstream.getRaw(any(), party.toString(), "image/png") }
    }

    /**
     * An absent path parameter is the caller's error. JAX-RS injects null for one, and a
     * non-nullable Kotlin parameter would turn the commonest mistake into a 500 (fleet rule: the
     * guard in the body would be dead code, because the intrinsic already threw).
     */
    @Test
    fun `a blank descriptor key is a 400, not a 500`() {
        val upstream = mockk<UpstreamClient>()

        val resp = resource(upstream).merchantLogo("   ", 64)

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { upstream.getRaw(any(), any(), any()) }
    }
}
