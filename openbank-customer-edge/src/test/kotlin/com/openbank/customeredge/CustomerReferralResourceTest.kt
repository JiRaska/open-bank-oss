// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.ratelimit.RateLimiter
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.CustomerReferralResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Member-get-member routes. What these tests exist to catch: a party id taken from anywhere but
 * the token, and a referee's identity or an invite token leaking to a referrer.
 */
class CustomerReferralResourceTest {

    private val caller: UUID = UUID.randomUUID()
    private val stranger: UUID = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")
    private val svc = "https://referral-service.referral.svc:8443"
    private val mapper = ObjectMapper()
    private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde"

    private fun resource(upstream: UpstreamClient, allowed: Boolean = true): CustomerReferralResource {
        val limiter = mockk<RateLimiter> { every { isWithinWindow(any(), any(), any(), any(), any()) } returns allowed }
        return CustomerReferralResource(
            upstream,
            mockk<CustomerPartyResolver> { every { resolve(any()) } returns caller },
            limiter,
            Clock.fixed(now, ZoneOffset.UTC),
        ).apply { referralServiceUrl = svc }
    }

    private fun json(response: Response): JsonNode = mapper.readTree(response.entity as String)

    private fun JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    // ---- GET /referrals/program ----

    @Test
    fun `program is the newest published one still inside its window, without maker or checker`() {
        val upstream = mockk<UpstreamClient>()
        val openId = UUID.randomUUID()
        every { upstream.get("$svc/api/v1/referrals/programs", caller.toString()) } returns Response.ok(
            """[{"id":"${UUID.randomUUID()}","status":"PUBLISHED","rewardAmount":900,"currency":"CZK",
                 "qualifyingEvent":"account.opened","attributionWindowEndsAt":"2026-09-01T00:00:00Z","maker":"m","checker":"c"},
                {"id":"$openId","name":"mgm","version":2,"status":"PUBLISHED","rewardAmount":500.0000,"currency":"CZK",
                 "qualifyingEvent":"account.opened","attributionWindowEndsAt":"2026-12-31T00:00:00Z","maker":"m","checker":"c"}]""",
        ).build()

        val response = resource(upstream).program()

        assertThat(response.status).isEqualTo(200)
        val out = json(response)
        assertThat(out.keys()).containsExactlyInAnyOrder(
            "id",
            "rewardAmount",
            "currency",
            "qualifyingEvent",
            "attributionWindowEndsAt",
        )
        assertThat(out["id"].asText()).isEqualTo(openId.toString())
        assertThat(out["rewardAmount"].isTextual).isTrue()
        assertThat(out["rewardAmount"].asText()).isEqualTo("500")
    }

    @Test
    fun `program is 404 when every published programme's window has ended`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            """[{"id":"${UUID.randomUUID()}","status":"PUBLISHED","rewardAmount":1,"currency":"CZK",
                 "qualifyingEvent":"account.opened","attributionWindowEndsAt":"2026-09-13T10:00:00Z"}]""",
        ).build()

        assertThat(resource(upstream).program().status).isEqualTo(404)
    }

    // ---- POST /referrals/invites ----

    @Test
    fun `an invite is issued as the token party with a party-namespaced key and only the four fields`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val body = slot<String>()
        val key = slot<String>()
        val programId = UUID.randomUUID()
        every { upstream.post(capture(url), caller.toString(), capture(body), capture(key)) } returns
            Response.status(201).entity(
                """{"id":"${UUID.randomUUID()}","programId":"$programId","token":"$token","referrerPartyId":"$caller",
                   "refereePartyId":null,"status":"ISSUED","expiresAt":"2026-12-31T00:00:00Z","idempotencyKey":"$caller:k1",
                   "attributedAt":null}""",
            ).build()

        val response = resource(
            upstream,
        ).issueInvite("""{"programId":"$programId","referrerPartyId":"$stranger"}""", "k1")

        assertThat(url.captured).isEqualTo("$svc/api/v1/referrals/programs/$programId/invites")
        assertThat(mapper.readTree(body.captured)["referrerPartyId"].asText()).isEqualTo(caller.toString())
        assertThat(body.captured).doesNotContain(stranger.toString())
        assertThat(key.captured).isEqualTo("$caller:k1")
        assertThat(response.status).isEqualTo(201)
        val out = json(response)
        assertThat(out.keys()).containsExactlyInAnyOrder("id", "token", "expiresAt", "status")
        assertThat(out["token"].asText()).isEqualTo(token)
    }

    @Test
    fun `an invite without a key or with a malformed programme id never reaches referral`() {
        val upstream = mockk<UpstreamClient>()

        assertThat(
            resource(upstream).issueInvite("""{"programId":"${UUID.randomUUID()}"}""", " ").status,
        ).isEqualTo(400)
        assertThat(resource(upstream).issueInvite("""{"programId":"nope"}""", "k").status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `invite conflicts say whether the key was reused or the programme is not open`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(any(), any(), any(), "$caller:reused") } returns
            Response.status(409).entity("""{"error":"x","reason":"IDEMPOTENCY_KEY_REUSED"}""").build()
        every { upstream.post(any(), any(), any(), "$caller:closed") } returns
            Response.status(409).entity("""{"error":"program is not published or has expired"}""").build()
        val programme = """{"programId":"${UUID.randomUUID()}"}"""

        val reused = resource(upstream).issueInvite(programme, "reused")
        val closed = resource(upstream).issueInvite(programme, "closed")

        assertThat(reused.status).isEqualTo(409)
        assertThat(json(reused)["reason"].asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(json(closed)["reason"].asText()).isEqualTo("PROGRAM_UNAVAILABLE")
    }

    // ---- GET /referrals/invites ----

    /**
     * The privacy property. Upstream is deliberately made to over-share (referee id and name, the
     * token, its hash, the idempotency key) — the edge projection must drop all of it regardless.
     */
    @Test
    fun `the invite list never carries referee identity or the token, even if upstream sends them`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), caller.toString()) } returns Response.ok(
            """[{"id":"${UUID.randomUUID()}","status":"ATTRIBUTED","createdAt":"2026-09-01T00:00:00Z",
                 "expiresAt":"2026-12-31T00:00:00Z","attributedAt":"2026-09-02T00:00:00Z",
                 "refereePartyId":"$stranger","refereeName":"Jana Nováková","token":"$token","tokenHash":"abc123",
                 "idempotencyKey":"$caller:k1",
                 "reward":{"status":"REWARDED","amount":"500.0000","currency":"CZK","requestedAt":"2026-09-03T00:00:00Z",
                           "rewardedAt":"2026-09-04T00:00:00Z","refereePartyId":"$stranger","rewardReference":"ref"}}]""",
        ).build()

        val response = resource(upstream).invites()

        assertThat(url.captured).isEqualTo("$svc/api/v1/referrals/parties/$caller/invites")
        assertThat(response.status).isEqualTo(200)
        val raw = response.entity as String
        assertThat(raw).doesNotContain(
            "refereePartyId", "refereeName", "token", "tokenHash", "idempotencyKey", "rewardReference",
            stranger.toString(), "Jana", this.token,
        )
        val invite = json(response)[0]
        assertThat(
            invite.keys(),
        ).containsExactlyInAnyOrder("id", "status", "createdAt", "expiresAt", "attributedAt", "reward")
        assertThat(
            invite["reward"].keys(),
        ).containsExactlyInAnyOrder("status", "amount", "currency", "requestedAt", "rewardedAt")
        assertThat(invite["reward"]["amount"].asText()).isEqualTo("500")
    }

    @Test
    fun `an ISSUED invite past its expiry is reported EXPIRED, a live one stays ISSUED`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            """[{"id":"${UUID.randomUUID()}","status":"ISSUED","createdAt":null,"expiresAt":"2026-12-31T00:00:00Z","reward":null},
                {"id":"${UUID.randomUUID()}","status":"ISSUED","createdAt":null,"expiresAt":"2026-09-13T09:59:59Z","reward":null},
                {"id":"${UUID.randomUUID()}","status":"ATTRIBUTED","expiresAt":"2026-01-01T00:00:00Z","reward":null}]""",
        ).build()

        val out = json(resource(upstream).invites())

        assertThat(out.map { it["status"].asText() }).containsExactly("ISSUED", "EXPIRED", "ATTRIBUTED")
        assertThat(out[0]["createdAt"].isNull).isTrue()
        assertThat(out[0]["reward"].isNull).isTrue()
    }

    // ---- POST /referrals/attributions ----

    @Test
    fun `attribution names the token party as referee and ignores a party id in the body`() {
        val upstream = mockk<UpstreamClient>()
        val base = slot<String>()
        val path = slot<String>()
        val body = slot<String>()
        val key = slot<String>()
        every {
            upstream.postToService(capture(base), capture(path), caller.toString(), capture(body), capture(key))
        } returns
            Response.ok(
                """{"id":"x","referrerPartyId":"$stranger","refereePartyId":"$caller","token":"hash"}""",
            ).build()

        val response = resource(upstream).attribute("""{"token":"$token","refereePartyId":"$stranger"}""", "a-1")

        assertThat(base.captured).isEqualTo(svc)
        assertThat(path.captured).isEqualTo("/api/v1/referrals/invites/$token/attribute")
        assertThat(mapper.readTree(body.captured)["refereePartyId"].asText()).isEqualTo(caller.toString())
        assertThat(body.captured).doesNotContain(stranger.toString())
        assertThat(key.captured).isEqualTo("a-1")
        assertThat(response.status).isEqualTo(200)
        assertThat(json(response).keys()).containsExactly("status")
        assertThat(json(response)["status"].asText()).isEqualTo("ATTRIBUTED")
    }

    @Test
    fun `attribution outcomes map to 404, 410 and 409 reasons without leaking upstream text`() {
        fun outcome(status: Int, body: String): Response {
            val upstream = mockk<UpstreamClient>()
            every { upstream.postToService(any(), any(), any(), any(), any()) } returns
                Response.status(status).entity(body).build()
            return resource(upstream).attribute("""{"token":"$token"}""", "a-2")
        }

        assertThat(outcome(404, """{"error":"invite not found"}""").status).isEqualTo(404)
        assertThat(outcome(409, """{"error":"invite has expired","reason":"EXPIRED"}""").status).isEqualTo(410)
        val self = outcome(409, """{"error":"self-referral is not allowed","reason":"SELF"}""")
        assertThat(self.status).isEqualTo(409)
        assertThat(json(self)["reason"].asText()).isEqualTo("SELF")
        assertThat(json(outcome(409, """{"reason":"ALREADY_ATTRIBUTED"}"""))["reason"].asText())
            .isEqualTo("ALREADY_ATTRIBUTED")
        assertThat(json(outcome(409, """{"reason":"NOT_ATTRIBUTABLE"}"""))["reason"].asText()).isEqualTo("REJECTED")
        assertThat(json(outcome(409, """{"error":"???"}"""))["reason"].asText()).isEqualTo("REJECTED")
        assertThat(outcome(503, """{"error":"db down"}""").status).isEqualTo(502)
    }

    @Test
    fun `attribution over the hourly quota is a 429 that never looks the token up`() {
        val upstream = mockk<UpstreamClient>()

        val response = resource(upstream, allowed = false).attribute("""{"token":"$token"}""", "a-3")

        assertThat(response.status).isEqualTo(429)
        verify(exactly = 0) { upstream.postToService(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a malformed token or missing key is a 400 before quota or upstream`() {
        val upstream = mockk<UpstreamClient>()

        assertThat(resource(upstream).attribute("""{"token":"../../programs/x"}""", "a-4").status).isEqualTo(400)
        assertThat(resource(upstream).attribute("""{"token":"$token"}""", null).status).isEqualTo(400)
        assertThat(resource(upstream).attribute("""not json""", "a-5").status).isEqualTo(400)
        verify(exactly = 0) { upstream.postToService(any(), any(), any(), any(), any()) }
    }
}
