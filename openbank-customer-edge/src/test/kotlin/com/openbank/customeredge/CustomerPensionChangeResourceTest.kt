// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.CustomerPensionChangeResource
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
 * The pension follow-up flows (pension-service API 1.2.0, `docs/ux/pension-client-flows.md`): the
 * document-bound SCA ids the edge hands out must be exactly the ones pension-service verifies, the
 * edge must never spend those challenges itself, and nothing reaches pension-service for a contract
 * the caller does not hold.
 */
class CustomerPensionChangeResourceTest {
    private val caller: UUID = UUID.randomUUID()
    private val contractId: UUID = UUID.randomUUID()
    private val payoutId: UUID = UUID.randomUUID()
    private val applicationId: UUID = UUID.randomUUID()
    private val challenge: UUID = UUID.randomUUID()
    private val pension = "http://pension.test"
    private val api = "$pension/api/v2/pension"
    private val mapper = ObjectMapper()
    private val doc = "a".repeat(64)

    private fun resource(upstream: UpstreamClient) =
        CustomerPensionChangeResource(upstream, mockk<CustomerPartyResolver> { every { resolve(null) } returns caller })
            .apply { pensionServiceUrl = pension }

    private fun json(response: Response): JsonNode = mapper.readTree(response.entity as String)

    private fun ok(body: String, status: Int = 200) = Response.status(status).entity(body).build()

    private fun owned(upstream: UpstreamClient, owner: UUID = caller) {
        every { upstream.get("$api/contracts/$contractId", caller.toString()) } returns
            ok("""{"contractId":"$contractId","participantPartyId":"$owner","status":"ACTIVE"}""")
    }

    private val schedule = """{"amount":"1500","frequency":"MONTHLY","dayOfMonth":15}"""

    @Test
    fun `a schedule change without a challenge previews and answers the exact pension-schedule-change id`() {
        val upstream = mockk<UpstreamClient>()
        owned(upstream)
        every {
            upstream.post(
                "$api/contracts/$contractId/contribution-schedule/preview",
                caller.toString(),
                any(),
                "k1:preview",
            )
        } returns
            ok("""{"effectiveFrom":"2026-11-15","documentSha256":"${doc.uppercase()}"}""")

        val response = resource(upstream).changeSchedule(contractId.toString(), schedule, "k1", null)

        assertThat(response.status).isEqualTo(403)
        val body = json(response)
        assertThat(body["code"].asText()).isEqualTo("SCA_REQUIRED")
        assertThat(body["scaLinking"]["approvalRequestId"].asText()).isEqualTo("pension-schedule-change:$doc")
        assertThat(body["scaLinking"]["payloadSha256"].asText()).isEqualTo(doc)
        assertThat(body["preview"]["effectiveFrom"].asText()).isEqualTo("2026-11-15")
        verify(exactly = 0) { upstream.post(match { it.endsWith("/changes") }, any(), any(), any()) }
    }

    @Test
    fun `a signed schedule change forwards the challenge unspent with the identical body`() {
        val upstream = mockk<UpstreamClient>()
        owned(upstream)
        val sent = slot<String>()
        every {
            upstream.post(
                "$api/contracts/$contractId/contribution-schedule/changes",
                caller.toString(),
                capture(sent),
                "k2",
            )
        } returns
            ok("""{"seq":2,"status":"PENDING"}""", 201)

        val response = resource(upstream).changeSchedule(contractId.toString(), schedule, "k2", challenge.toString())

        assertThat(response.status).isEqualTo(201)
        val body = mapper.readTree(sent.captured)
        assertThat(body["scaChallengeId"].asText()).isEqualTo(challenge.toString())
        assertThat(body["dayOfMonth"].asInt()).isEqualTo(15)
        assertThat(body["acknowledgeIncentiveReduction"].asBoolean()).isFalse()
    }

    @Test
    fun `beneficiary shares must total exactly 100 before anything goes upstream`() {
        val upstream = mockk<UpstreamClient>()

        val response = resource(upstream).previewBeneficiaries(
            contractId.toString(),
            """{"beneficiaries":[{"name":"A","sharePercent":"60"},{"name":"B","sharePercent":"30"}]}""",
            "k3",
        )

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `another party's contract is 404 and no change route is called`() {
        val upstream = mockk<UpstreamClient>()
        owned(upstream, UUID.randomUUID())

        val response = resource(upstream).changeBeneficiaries(
            contractId.toString(),
            """{"beneficiaries":[{"name":"A","sharePercent":"100"}]}""",
            "k4",
            challenge.toString(),
        )

        assertThat(response.status).isEqualTo(404)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a mandate cancellation hands out pension-mandate-cancellation over contract and mandate`() {
        val upstream = mockk<UpstreamClient>()
        owned(upstream)
        val mandate = UUID.randomUUID()
        val expected = sha("pension-mandate-cancel|$contractId|$mandate")

        val response = resource(upstream).cancelMandate(contractId.toString(), mandate.toString(), "k5", null)

        assertThat(response.status).isEqualTo(403)
        assertThat(json(response)["scaLinking"]["approvalRequestId"].asText())
            .isEqualTo("pension-mandate-cancellation:$expected")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `annuity selection reads the chosen offer's selection hash and never pre-selects`() {
        val upstream = mockk<UpstreamClient>()
        owned(upstream)
        val hash = "b".repeat(64)
        every { upstream.get("$api/contracts/$contractId/exit/payouts/$payoutId/annuity", caller.toString()) } returns
            ok(
                """{"status":"OFFERED","offers":[
                {"partnerId":"p1","offerId":"o1","selectionHash":"${"c".repeat(64)}"},
                {"partnerId":"p2","offerId":"o2","selectionHash":"$hash"}]}""",
            )
        val selection = """{"partnerId":"p2","offerId":"o2"}"""

        val missing = resource(
            upstream,
        ).selectAnnuity(contractId.toString(), payoutId.toString(), selection, "k6", null)
        val unknown = resource(upstream).selectAnnuity(
            contractId.toString(),
            payoutId.toString(),
            """{"partnerId":"p9","offerId":"o2"}""",
            "k7",
            null,
        )

        assertThat(
            json(missing)["scaLinking"]["approvalRequestId"].asText(),
        ).isEqualTo("pension-annuity-selection:$hash")
        assertThat(unknown.status).isEqualTo(409)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `annuity cancellation uses the purchase's cancellation hash and forwards a challenge`() {
        val upstream = mockk<UpstreamClient>()
        owned(upstream)
        val hash = "d".repeat(64)
        val url = "$api/contracts/$contractId/exit/payouts/$payoutId/annuity"
        every { upstream.get(url, caller.toString()) } returns ok("""{"status":"ACTIVE","cancellationHash":"$hash"}""")
        val sent = slot<String>()
        every { upstream.post("$url/cancellation", caller.toString(), capture(sent), "k9") } returns
            ok("""{"status":"CANCELLED"}""")

        val missing = resource(upstream).cancelAnnuity(contractId.toString(), payoutId.toString(), "k8", null)
        val cancelled = resource(
            upstream,
        ).cancelAnnuity(contractId.toString(), payoutId.toString(), "k9", challenge.toString())

        assertThat(
            json(missing)["scaLinking"]["approvalRequestId"].asText(),
        ).isEqualTo("pension-annuity-cancellation:$hash")
        assertThat(cancelled.status).isEqualTo(200)
        assertThat(mapper.readTree(sent.captured)["scaChallengeId"].asText()).isEqualTo(challenge.toString())
    }

    @Test
    fun `a questionnaire draft needs an Idempotency-Key and closed answers`() {
        val upstream = mockk<UpstreamClient>()

        val noKey = resource(
            upstream,
        ).saveDraft(applicationId.toString(), """{"answers":{"goal":"RETIREMENT"}}""", null)
        val freeText = resource(
            upstream,
        ).saveDraft(applicationId.toString(), """{"answers":{"goal":"I want money"}}""", "k")

        assertThat(listOf(noKey, freeText).map { it.status }).containsOnly(400)
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `contradicting answers come back as codes and question ids only`() {
        val upstream = mockk<UpstreamClient>()
        every {
            upstream.post(
                "$api/onboarding/applications/$applicationId/warnings/acknowledge",
                caller.toString(),
                any(),
                "k10",
            )
        } returns
            ok("""{"error":"x","inconsistencies":[{"code":"RISK_VS_LOSS","questions":["q1","q2"],"debug":"y"}]}""", 422)

        val response = resource(upstream).acknowledgeWarnings(
            applicationId.toString(),
            """{"strategyCode":"DYNAMIC","warnings":["STRATEGY_ABOVE_PROFILE"],"language":"cs"}""",
            "k10",
        )

        val body = json(response)
        assertThat(response.status).isEqualTo(422)
        assertThat(body["code"].asText()).isEqualTo("INCONSISTENT_ANSWERS")
        assertThat(body["inconsistencies"][0]["questions"].map { it.asText() }).containsExactly("q1", "q2")
        assertThat(response.entity as String).doesNotContain("debug")
    }

    @Test
    fun `another party's application is 404 on the questionnaire routes`() {
        val upstream = mockk<UpstreamClient>()
        every {
            upstream.get("$api/onboarding/applications/$applicationId/questionnaire?lang=en", caller.toString())
        } returns
            ok("""{"error":"not yours"}""", 404)

        val response = resource(upstream).questionSet(applicationId.toString(), "en")

        assertThat(response.status).isEqualTo(404)
        assertThat(response.entity as String).doesNotContain("not yours")
    }

    private fun sha(document: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(document.toByteArray()).joinToString("") { "%02x".format(it) }
}
