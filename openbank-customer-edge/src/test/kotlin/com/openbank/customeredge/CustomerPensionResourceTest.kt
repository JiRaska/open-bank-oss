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
 * Customer pension routes (ADR-0334 S6) against pension-service API 1.2.0. pension-service takes
 * eligibility facts from its caller, so every property asserted here is one only the edge
 * provides. The edge never calls pension-fund-service (its holdings are pension-service's alone).
 */
class CustomerPensionResourceTest {
    private val caller: UUID = UUID.randomUUID()
    private val stranger: UUID = UUID.randomUUID()
    private val contractId: UUID = UUID.randomUUID()
    private val pension = "http://pension.test"
    private val catalog = "http://catalog.test"
    private val sca = "http://sca.test"
    private val partySvc = "http://party.test"
    private val challenge: UUID = UUID.randomUUID()
    private val mapper = ObjectMapper()

    private fun resource(upstream: UpstreamClient) =
        CustomerPensionResource(upstream, mockk<CustomerPartyResolver> { every { resolve(null) } returns caller })
            .apply {
                pensionServiceUrl = pension
                catalogUrl = catalog
                scaServiceUrl = sca
                partyServiceUrl = partySvc
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

    private val validApplication = """
        {"productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
         "providerType":"PENSION_COMPANY","birthDate":"2010-01-01","residencyCountry":"XX",
         "schedule":{"amount":"1000","currency":"CZK","frequency":"MONTHLY"},
         "participantPartyId":"$stranger"}
    """.trimIndent()

    private fun partyRecord(upstream: UpstreamClient, body: String) {
        every { upstream.get("$partySvc/api/v1/parties/$caller", caller.toString()) } returns ok(body)
    }

    @Test
    fun `an application carries birth date and residency from the party record, never from the app`() {
        val upstream = mockk<UpstreamClient>()
        partyRecord(upstream, """{"id":"$caller","dateOfBirth":"1985-05-05","address":{"countryCode":"cz"}}""")
        val party = slot<String>()
        val sent = slot<String>()
        val key = slot<String>()
        every {
            upstream.post(
                "$pension/api/v1/pension/onboarding/applications",
                capture(party),
                capture(sent),
                capture(key),
            )
        } returns ok("""{"applicationId":"${UUID.randomUUID()}","status":"STARTED"}""", 201)

        val response = resource(upstream).startApplication(validApplication, "key-1")

        assertThat(response.status).isEqualTo(201)
        assertThat(party.captured).isEqualTo(caller.toString())
        assertThat(key.captured).isEqualTo("key-1")
        val upstreamBody = mapper.readTree(sent.captured)
        assertThat(upstreamBody["birthDate"].asText()).isEqualTo("1985-05-05")
        assertThat(upstreamBody["residencyCountry"].asText()).isEqualTo("CZ")
        assertThat(sent.captured).doesNotContain(stranger.toString())
    }

    @Test
    fun `an incomplete party record refuses the application and never sends a null residency`() {
        val upstream = mockk<UpstreamClient>()
        partyRecord(upstream, """{"id":"$caller","dateOfBirth":"1985-05-05","address":null}""")

        val response = resource(upstream).startApplication(validApplication, "key-1")

        assertThat(response.status).isEqualTo(422)
        assertThat(json(response)["code"].asText()).isEqualTo("PARTY_PROFILE_INCOMPLETE")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a state-changing POST without an Idempotency-Key is refused before any upstream call`() {
        val upstream = mockk<UpstreamClient>()

        val started = resource(upstream).startApplication(validApplication, null)
        val paused = resource(upstream).pause(contractId.toString(), " ", challenge.toString())
        val quoted = resource(upstream).terminationQuote(contractId.toString(), null)

        assertThat(listOf(started, paused, quoted).map { it.status }).containsOnly(400)
        verify(exactly = 0) { upstream.get(any(), any()) }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a transfer-in is an application of kind TRANSFER_IN on the S2 route`() {
        val upstream = mockk<UpstreamClient>()
        partyRecord(upstream, """{"dateOfBirth":"1985-05-05","address":{"countryCode":"CZ"}}""")
        val sent = slot<String>()
        every {
            upstream.post("$pension/api/v1/pension/contracts/transfers-in", caller.toString(), capture(sent), "key-2")
        } returns ok("""{"applicationId":"${UUID.randomUUID()}","kind":"TRANSFER_IN"}""", 201)
        val body = validApplication.replace(
            "\"participantPartyId\"",
            "\"transferIn\":{\"providerId\":\"P1\",\"providerName\":\"Old PF\",\"contractNumber\":\"123\"}," +
                "\"participantPartyId\"",
        )

        val response = resource(upstream).transferIn(body, "key-2")

        assertThat(response.status).isEqualTo(201)
        val upstreamBody = mapper.readTree(sent.captured)
        assertThat(upstreamBody["kind"].asText()).isEqualTo("TRANSFER_IN")
        assertThat(upstreamBody["transferIn"]["contractNumber"].asText()).isEqualTo("123")
    }

    @Test
    fun `signing an application forwards the challenge to pension-service and refuses a missing one`() {
        val upstream = mockk<UpstreamClient>()
        val applicationId = UUID.randomUUID()
        val sent = slot<String>()
        every {
            upstream.post(
                "$pension/api/v1/pension/onboarding/applications/$applicationId/sign",
                caller.toString(),
                capture(sent),
                "key-3",
            )
        } returns ok("""{"applicationId":"$applicationId","status":"SIGNED"}""")

        val missing = resource(upstream).signApplication(applicationId.toString(), "key-3", null)
        val signed = resource(upstream).signApplication(applicationId.toString(), "key-3", challenge.toString())

        assertThat(missing.status).isEqualTo(403)
        assertThat(signed.status).isEqualTo(200)
        assertThat(mapper.readTree(sent.captured)["scaChallengeId"].asText()).isEqualTo(challenge.toString())
        verify(exactly = 0) { upstream.post(match { it.startsWith(sca) }, any(), any(), any()) }
    }

    @Test
    fun `another party's application is 404, as an unknown one`() {
        val upstream = mockk<UpstreamClient>()
        val applicationId = UUID.randomUUID()
        every {
            upstream.get("$pension/api/v1/pension/onboarding/applications/$applicationId", caller.toString())
        } returns
            ok("""{"error":"not yours"}""", 403)

        val response = resource(upstream).application(applicationId.toString())

        assertThat(response.status).isEqualTo(404)
        assertThat(response.entity as String).doesNotContain("not yours")
    }

    @Test
    fun `another party's contract is 404 and the unit register is never read`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(stranger))

        val response = resource(upstream).contract(contractId.toString())

        assertThat(response.status).isEqualTo(404)
        verify(exactly = 0) { upstream.get(match { it.contains("pension-fund") }, any()) }
    }

    @Test
    fun `a malformed contract id is 404 without any upstream call`() {
        val upstream = mockk<UpstreamClient>()

        assertThat(resource(upstream).contract("1-1-1-1-1x").status).isEqualTo(404)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `the overview drops the participant id and reads nothing but the contract`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(caller))

        val body = json(resource(upstream).contract(contractId.toString()))

        assertThat(body["contractId"].asText()).isEqualTo(contractId.toString())
        assertThat(body.has("participantPartyId")).isFalse()
        verify(exactly = 1) { upstream.get(any(), any()) }
        verify(exactly = 0) { upstream.get(any()) }
    }

    @Test
    fun `early termination is the S5 quote then a signed notice that forwards the challenge and the account`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val noticeId = UUID.randomUUID()
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        every { upstream.post(capture(urls), caller.toString(), capture(bodies), any()) } answers {
            if (firstArg<String>().endsWith("/quote")) {
                ok("""{"noticeId":"$noticeId","status":"QUOTED","quoteHash":"h"}""", 201)
            } else {
                ok("""{"noticeId":"$noticeId","status":"SIGNED"}""")
            }
        }

        val quoted = resource(upstream).terminationQuote(contractId.toString(), "key-4")
        val signed = resource(upstream).signTermination(
            contractId.toString(),
            noticeId.toString(),
            """{"payoutIban":"CZ65 0800 0000 1920 0014 5399","currentValue":"1"}""",
            "key-5",
            challenge.toString(),
        )

        assertThat(quoted.status).isEqualTo(201)
        assertThat(signed.status).isEqualTo(200)
        assertThat(urls).containsExactly(
            "$pension/api/v1/pension/contracts/$contractId/exit/termination/quote",
            "$pension/api/v1/pension/contracts/$contractId/exit/termination/$noticeId/sign",
        )
        val signBody = mapper.readTree(bodies[1])
        assertThat(signBody["payoutIban"].asText()).isEqualTo("CZ6508000000192000145399")
        assertThat(signBody["scaChallengeId"].asText()).isEqualTo(challenge.toString())
        assertThat(signBody.has("currentValue")).isFalse()
        verify(exactly = 0) { upstream.get(match { it.contains("pension-fund") }, any()) }
    }

    @Test
    fun `a payout account change goes to the SCA-bound S8 route with the challenge forwarded`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val payoutId = UUID.randomUUID()
        val sent = slot<String>()
        every {
            upstream.put(
                "$pension/api/v1/pension/contracts/$contractId/exit/payouts/$payoutId/account",
                caller.toString(),
                capture(sent),
            )
        } returns ok("""{"payoutId":"$payoutId","pendingAccountLast4":"5399"}""")

        val missing = resource(upstream).changePayoutAccount(
            contractId.toString(),
            payoutId.toString(),
            """{"payoutIban":"CZ6508000000192000145399"}""",
            null,
        )
        val changed = resource(upstream).changePayoutAccount(
            contractId.toString(),
            payoutId.toString(),
            """{"payoutIban":"CZ6508000000192000145399"}""",
            challenge.toString(),
        )

        assertThat(missing.status).isEqualTo(403)
        assertThat(changed.status).isEqualTo(200)
        assertThat(mapper.readTree(sent.captured)["scaChallengeId"].asText()).isEqualTo(challenge.toString())
    }

    @Test
    fun `an upstream rule refusal is a fixed code and never the upstream body`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$pension/api/v1/pension/contracts/$contractId", caller.toString()) } returns
            ok(contract(caller))
        every { upstream.post(match { it.startsWith(sca) }, any(), any(), any()) } returns
            ok("""{"status":"COMPLETED"}""")
        every { upstream.post(match { it.startsWith(pension) }, any(), any(), any()) } returns
            ok("""{"error":"pack CZ/DPS v1 internal detail"}""", 400)

        val response = resource(upstream).pause(contractId.toString(), "key-r", challenge.toString())

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

        val quoted = resource(upstream).payout(contractId.toString(), """{"form":"LUMP_SUM"}""", "key-6")
        val confirmed = resource(upstream).confirmPayout(
            contractId.toString(),
            "not-a-uuid",
            """{"payoutIban":"CZ6508000000192000145399"}""",
            "key-7",
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

        val response = resource(upstream).pause(contractId.toString(), "key-p", null)

        assertThat(response.status).isEqualTo(403)
        val body = json(response)
        assertThat(body["code"].asText()).isEqualTo("SCA_REQUIRED")
        assertThat(body["scaLinking"]["purpose"].asText()).isEqualTo("APPROVAL")
        assertThat(body["scaLinking"]["approvalRequestId"].asText()).isEqualTo("pension.suspend:$contractId")
        assertThat(body["scaLinking"]["payloadSha256"].asText()).matches("[0-9a-f]{64}")
        verify(exactly = 0) { upstream.put(any(), any(), any()) }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a challenge signed for a different payload is refused by sca-service and the change is not made`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val consumed = mutableListOf<String>()
        // sca-service compares both fields strictly; the challenge in this test signed a RESUME.
        val signed = resource(upstream).resume(contractId.toString(), "key-s", null)
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

        val response = resource(upstream).pause(contractId.toString(), "key-t", challenge.toString())

        assertThat(response.status).isEqualTo(403)
        assertThat(json(response)["code"].asText()).isEqualTo("SCA_REJECTED")
        val sent = mapper.readTree(consumed.single())
        assertThat(sent["partyId"].asText()).isEqualTo(caller.toString())
        assertThat(sent["approvalRequestId"].asText()).isEqualTo("pension.suspend:$contractId")
        assertThat(sent["payloadSha256"].asText()).isNotEqualTo(signed)
        verify(exactly = 0) { upstream.post(match { it.startsWith(pension) }, any(), any(), any()) }
    }

    @Test
    fun `a challenge signed for exactly this payload is consumed and the change goes upstream`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val signed = resource(upstream).pause(contractId.toString(), "key-u", null)
            .let { json(it)["scaLinking"]["payloadSha256"].asText() }
        every {
            upstream.post("$sca/api/v1/sca/challenges/$challenge/consume", caller.toString(), any(), any())
        } answers
            {
                if (mapper.readTree(thirdArg<String>())["payloadSha256"].asText() == signed) ok("{}") else ok("{}", 409)
            }
        every {
            upstream.post("$pension/api/v1/pension/contracts/$contractId/suspend", caller.toString(), "{}", "key-u")
        } returns
            ok(contract(caller, "SUSPENDED"))

        val response = resource(upstream).pause(contractId.toString(), "key-u", challenge.toString())

        assertThat(response.status).isEqualTo(200)
        verify(exactly = 1) {
            upstream.post("$pension/api/v1/pension/contracts/$contractId/suspend", any(), any(), any())
        }
    }

    @Test
    fun `pausing, the termination notice and a payout confirmation all refuse a missing challenge`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)

        val paused = resource(upstream).pause(contractId.toString(), "k1", null)
        val notice = resource(upstream).signTermination(
            contractId.toString(),
            UUID.randomUUID().toString(),
            """{"payoutIban":"CZ6508000000192000145399"}""",
            "k2",
            null,
        )
        val confirmed = resource(upstream).confirmPayout(
            contractId.toString(),
            UUID.randomUUID().toString(),
            """{"payoutIban":"CZ6508000000192000145399"}""",
            "k3",
            null,
        )

        assertThat(listOf(paused, notice, confirmed).map { it.status }).containsOnly(403)
        assertThat(listOf(paused, notice, confirmed).map { json(it)["code"].asText() }).containsOnly("SCA_REQUIRED")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a strategy change is document-bound with the exact pension-strategy-change id, then forwarded unspent`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val body =
            """{"strategyCode":"DYNAMIC","effectiveFrom":"2999-01-01","acknowledgedWarnings":["STRATEGY_ABOVE_PROFILE"]}"""
        val expected = sha("pension-strategy-change|$contractId|DYNAMIC|2999-01-01|STRATEGY_ABOVE_PROFILE")

        val missing = resource(upstream).strategy(contractId.toString(), body, "key-s1", null)
        val sent = slot<String>()
        every {
            upstream.put(
                "$pension/api/v1/pension/contracts/$contractId/strategy",
                caller.toString(),
                capture(sent),
                "key-s1",
                emptyMap(),
            )
        } returns ok(contract(caller))
        val changed = resource(upstream).strategy(contractId.toString(), body, "key-s1", challenge.toString())

        assertThat(missing.status).isEqualTo(403)
        assertThat(
            json(missing)["scaLinking"]["approvalRequestId"].asText(),
        ).isEqualTo("pension-strategy-change:$expected")
        assertThat(json(missing)["scaLinking"]["payloadSha256"].asText()).isEqualTo(expected)
        assertThat(changed.status).isEqualTo(200)
        assertThat(mapper.readTree(sent.captured)["scaChallengeId"].asText()).isEqualTo(challenge.toString())
        verify(exactly = 0) { upstream.post(match { it.startsWith(sca) }, any(), any(), any()) }
    }

    @Test
    fun `a mandate set-up hands out pension-mandate-setup over the exact mandate and never consumes the challenge`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val body = """{"kind":"STANDING_ORDER","debtorIban":"cz65 0800 0000 1920 0014 5399","amount":"500.50",
            "currency":"CZK","firstCollection":"2999-02-01"}"""
        val expected =
            sha("pension-mandate-setup|$contractId|STANDING_ORDER|CZ6508000000192000145399|500.5|CZK|2999-02-01")

        val missing = resource(upstream).contributionMandate(contractId.toString(), body, "key-m", null)

        assertThat(missing.status).isEqualTo(403)
        assertThat(
            json(missing)["scaLinking"]["approvalRequestId"].asText(),
        ).isEqualTo("pension-mandate-setup:$expected")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a reassessment conflict passes reason and application id, nothing else`() {
        val upstream = mockk<UpstreamClient>()
        ownedContract(upstream)
        val app = UUID.randomUUID()
        every { upstream.put(any(), any(), any(), any(), any()) } returns ok(
            """{"error":"internal","code":"REASSESSMENT_REQUIRED","reason":"EXPIRED","applicationId":"$app","x":"y"}""",
            409,
        )

        val response = resource(upstream).strategy(
            contractId.toString(),
            """{"strategyCode":"DYNAMIC"}""",
            "key-ra",
            challenge.toString(),
        )

        val body = json(response)
        assertThat(response.status).isEqualTo(409)
        assertThat(body["code"].asText()).isEqualTo("REASSESSMENT_REQUIRED")
        assertThat(body["reason"].asText()).isEqualTo("EXPIRED")
        assertThat(body["applicationId"].asText()).isEqualTo(app.toString())
        assertThat(body.has("x")).isFalse()
        assertThat(response.entity as String).doesNotContain("internal")
    }

    private fun sha(document: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(document.toByteArray()).joinToString("") { "%02x".format(it) }
}
