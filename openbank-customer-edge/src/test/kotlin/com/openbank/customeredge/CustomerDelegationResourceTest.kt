// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.CustomerDelegationResource
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
 * The single property these routes exist to hold: **the party identity comes from the token, never
 * from the client** (ADR-0232 D6, ADR-0065). delegation-service re-checks it via
 * `X-Customer-Party-Id`, so these are the outer half of a pair — but the outer half is the one the
 * app actually goes through, and a mistake here hands a customer another party's grants.
 *
 * Every assertion below therefore names a party the caller is NOT and proves it does not reach
 * upstream. A test that only checked "the URL contains a UUID" would pass against exactly the bug
 * worth catching.
 */
class CustomerDelegationResourceTest {

    private val caller: UUID = UUID.randomUUID()
    private val stranger: UUID = UUID.randomUUID()
    private val svc = "http://delegation-service.delegation.svc:8126"
    private val auditSvc = "http://audit-service.audit.svc:8113"

    private fun resource(upstream: UpstreamClient): CustomerDelegationResource =
        CustomerDelegationResource(upstream).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns caller.toString()
                every { subject } returns caller.toString()
            }
            delegationServiceUrl = svc
            auditServiceUrl = auditSvc
        }

    @Test
    fun `sharedByMe scopes the upstream path to the token party`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()

        resource(upstream).sharedByMe()

        assertThat(url.captured).isEqualTo("$svc/api/v1/delegations/grantor/$caller")
    }

    @Test
    fun `sharedWithMe scopes the upstream path to the token party`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()

        resource(upstream).sharedWithMe()

        assertThat(url.captured).isEqualTo("$svc/api/v1/delegations/grantee/$caller")
    }

    @Test
    fun `every read forwards the token party as the upstream party header`() {
        val upstream = mockk<UpstreamClient>()
        val party = slot<String>()
        every { upstream.get(any(), capture(party)) } returns Response.ok("[]").build()

        resource(upstream).getById(UUID.randomUUID())

        assertThat(party.captured).isEqualTo(caller.toString())
    }

    @Test
    fun `offer fills in the grantor when the client omits it`() {
        val upstream = mockk<UpstreamClient>()
        val body = slot<String>()
        every { upstream.post(any(), any(), capture(body), any()) } returns Response.status(201).build()

        val response = resource(upstream).offer("""{"granteePartyId":"$stranger","resourceType":"ACCOUNT"}""")

        assertThat(response.status).isEqualTo(201)
        assertThat(body.captured).contains("\"grantorPartyId\":\"$caller\"")
        // The rest of the body is delegation-service's business and must survive untouched.
        assertThat(body.captured).contains("\"granteePartyId\":\"$stranger\"")
        assertThat(body.captured).contains("\"resourceType\":\"ACCOUNT\"")
    }

    @Test
    fun `preview derives grantor strips SCA and calls the literal upstream preview path`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val body = slot<String>()
        every { upstream.post(capture(url), any(), capture(body), any()) } returns
            Response.ok("{\"valid\":true}").build()

        val response = resource(upstream).preview(
            """{"granteePartyId":"$stranger","resourceType":"ACCOUNT","resourceId":"$GRANT_ID",""" +
                """"capabilities":["ACCOUNT_READ_BALANCES"],"grantScaSessionId":"$GRANT_ID"}""",
        )

        assertThat(response.status).isEqualTo(200)
        assertThat(url.captured).isEqualTo("$svc/api/v1/delegations/preview")
        assertThat(body.captured).contains("\"grantorPartyId\":\"$caller\"")
        assertThat(body.captured).doesNotContain("grantScaSessionId")
    }

    @Test
    fun `preview rejects another grantor and unsupported ceilings before upstream`() {
        val upstream = mockk<UpstreamClient>()

        val wrongGrantor = resource(upstream).preview(
            """{"grantorPartyId":"$stranger","granteePartyId":"$caller"}""",
        )
        val unenforced = resource(upstream).preview(
            """{"granteePartyId":"$stranger","dailyLimit":{"amount":100,"currency":"CZK"}}""",
        )

        assertThat(wrongGrantor.status).isEqualTo(403)
        assertThat(unenforced.status).isEqualTo(400)
        assertThat(unenforced.entity.toString()).contains("CUMULATIVE_LIMIT_UNSUPPORTED")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `offer REJECTS a body naming someone else as grantor instead of rewriting it`() {
        val upstream = mockk<UpstreamClient>()

        val response = resource(upstream).offer("""{"grantorPartyId":"$stranger","granteePartyId":"$caller"}""")

        assertThat(response.status).isEqualTo(403)
        // Silently rewriting would issue a grant the user never asked for — nothing may reach upstream.
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `offer accepts a body that names the caller as grantor`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(any(), any(), any(), any()) } returns Response.status(201).build()

        val response = resource(upstream).offer("""{"grantorPartyId":"$caller","granteePartyId":"$stranger"}""")

        assertThat(response.status).isEqualTo(201)
    }

    /**
     * A ceiling the platform cannot keep must not reach upstream, and above all must not reach the
     * customer as a 201. Asserting `upstream.post` is never called: the defect being fixed is that
     * this body used to sail through the edge, be stored, and come back echoed — the grantor walked
     * away believing the delegate was capped at 5 000 Kč/den while only `perTransactionLimit` was
     * ever checked on a payment.
     */
    @Test
    fun `offer rejects a body carrying dailyLimit or monthlyLimit`() {
        val upstream = mockk<UpstreamClient>()

        val daily = resource(upstream).offer(
            """{"granteePartyId":"$stranger","dailyLimit":{"amount":5000.00,"currency":"CZK"}}""",
        )
        assertThat(daily.status).isEqualTo(400)
        assertThat(daily.entity.toString()).contains("CUMULATIVE_LIMIT_UNSUPPORTED").contains("dailyLimit")

        val monthly = resource(upstream).offer(
            """{"granteePartyId":"$stranger","monthlyLimit":{"amount":50000.00,"currency":"CZK"}}""",
        )
        assertThat(monthly.status).isEqualTo(400)
        assertThat(monthly.entity.toString()).contains("monthlyLimit")

        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    /**
     * The control without which the test above is vacuous: an explicit JSON `null` is the app
     * sending "no ceiling", not a ceiling, and `perTransactionLimit` is the one limit this platform
     * enforces — both must still pass through. A naive `node.has(field)` check fails the first of
     * these, and a check that rejected all limits would fail the second while looking correct.
     */
    @Test
    fun `offer passes through a null ceiling and a perTransactionLimit`() {
        val upstream = mockk<UpstreamClient>()
        val body = slot<String>()
        every { upstream.post(any(), any(), capture(body), any()) } returns Response.status(201).build()

        val response = resource(upstream).offer(
            """{"granteePartyId":"$stranger","dailyLimit":null,"monthlyLimit":null,""" +
                """"perTransactionLimit":{"amount":5000.00,"currency":"CZK"}}""",
        )

        assertThat(response.status).isEqualTo(201)
        assertThat(body.captured).contains("\"perTransactionLimit\"")
    }

    @Test
    fun `offer rejects a body that is not a JSON object`() {
        val upstream = mockk<UpstreamClient>()

        assertThat(resource(upstream).offer("[]").status).isEqualTo(400)
        assertThat(resource(upstream).offer("not json").status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `accept sends the token party as grantee and the client's SCA session`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.post(capture(url), any(), any(), any()) } returns Response.ok().build()
        val sca = UUID.randomUUID()

        resource(upstream).accept(GRANT_ID, sca)

        assertThat(url.captured)
            .isEqualTo("$svc/api/v1/delegations/$GRANT_ID/accept?granteePartyId=$caller&scaSessionId=$sca")
        assertThat(url.captured).doesNotContain(stranger.toString())
    }

    @Test
    fun `accept without an SCA session is rejected at the edge`() {
        val upstream = mockk<UpstreamClient>()

        val response = resource(upstream).accept(GRANT_ID, null)

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `decline and renounce identify the grantee from the token`() {
        val upstream = mockk<UpstreamClient>()
        val urls = mutableListOf<String>()
        every { upstream.post(capture(urls), any(), any(), any()) } returns Response.ok().build()

        resource(upstream).decline(GRANT_ID)
        resource(upstream).renounce(GRANT_ID)

        assertThat(urls).containsExactly(
            "$svc/api/v1/delegations/$GRANT_ID/decline?granteePartyId=$caller",
            "$svc/api/v1/delegations/$GRANT_ID/renounce?granteePartyId=$caller",
        )
    }

    @Test
    fun `revoke never sends revokedBy, so the actor stays derived upstream`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val body = slot<String>()
        every { upstream.delete(capture(url), any(), capture(body)) } returns Response.ok().build()

        resource(upstream).revoke(GRANT_ID, "no longer needed")

        assertThat(url.captured).isEqualTo("$svc/api/v1/delegations/$GRANT_ID")
        // #3164: a caller-supplied actor let anyone revoke anyone's grant and sign it as someone else.
        assertThat(url.captured).doesNotContain("revokedBy")
        assertThat(body.captured).contains("no longer needed")
    }

    @Test
    fun `revoke supplies a default reason when the client sends none`() {
        val upstream = mockk<UpstreamClient>()
        val body = slot<String>()
        every { upstream.delete(any(), any(), capture(body)) } returns Response.ok().build()

        resource(upstream).revoke(GRANT_ID, null)

        assertThat(body.captured).contains("reason")
        assertThat(body.captured).doesNotContain("null")
    }

    // ── the grantor transparency view (ADR-0232 D5, #2990 AC10) ────────────────────────────────

    @Test
    fun `activity asks audit-service about the TOKEN party, never a client-named grantor`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val party = slot<String>()
        every { upstream.get(capture(url), capture(party)) } returns Response.ok("[]").build()

        resource(upstream).activity(null, null, null)

        assertThat(url.captured).isEqualTo("$auditSvc/api/v1/audit/on-behalf-of/$caller")
        assertThat(url.captured).doesNotContain(stranger.toString())
        assertThat(party.captured).isEqualTo(caller.toString())
    }

    @Test
    fun `activity passes the narrowing filters through`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()

        resource(upstream).activity(stranger.toString(), GRANT_ID.toString(), 25)

        // The filters can only ever REMOVE rows from a set already scoped to the caller, so
        // passing a party the caller is not is safe here — and must still not become the subject.
        assertThat(url.captured).startsWith("$auditSvc/api/v1/audit/on-behalf-of/$caller?")
        assertThat(url.captured).contains("delegatePartyId=$stranger")
        assertThat(url.captured).contains("delegationId=$GRANT_ID")
        assertThat(url.captured).contains("limit=25")
    }

    /**
     * The value is interpolated into a URL the edge calls with its own M2M identity. An unencoded
     * `&` would let a customer append query parameters of their choosing to that upstream call.
     */
    @Test
    fun `activity URL-encodes the filters instead of interpolating them raw`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()

        resource(upstream).activity("x&limit=999&delegatePartyId=$stranger", null, null)

        assertThat(url.captured).doesNotContain("&limit=999")
        assertThat(url.captured).contains("%26")
    }

    @Test
    fun `activity clamps an absurd page size`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()

        resource(upstream).activity(null, null, 100_000)

        assertThat(url.captured).endsWith("limit=500")
    }

    @Test
    fun `activity ignores blank filters rather than sending empty parameters`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()

        resource(upstream).activity("", "  ", null)

        assertThat(url.captured).isEqualTo("$auditSvc/api/v1/audit/on-behalf-of/$caller")
    }

    private companion object {
        val GRANT_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    }
}
