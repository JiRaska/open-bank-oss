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
 * Unit tests for CustomerEdgeResource business logic (ADR-0069).
 * Integration tests (endpoint + OIDC) are covered by the @QuarkusTest suite; here
 * we focus on the party-id claim resolution logic which is purely algorithmic.
 */
@Suppress("LargeClass") // one test class mirrors one large resource (CustomerEdgeResource is @Suppress'd too)
class CustomerEdgeResourceTest {

    // ── read ownership (IDOR guard) for getAccount / getBalance (finding A1) ─
    // account-service and balance-service scope reads by id only, so the edge must
    // verify the account belongs to the JWT party before proxying. Mirrors the guard
    // already applied to transactions/statements/payments.

    private fun resourceFor(upstream: UpstreamClient, callerParty: UUID): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns callerParty.toString()
            every { subject } returns callerParty.toString()
        }
        objectMapper = ObjectMapper()
        accountServiceUrl = "http://account"
        balanceServiceUrl = "http://balance"
        engagementServiceUrl = "http://engagement"
        campaignServiceUrl = "http://campaign"
        productCatalogUrl = "http://catalog"
        incentiveServiceUrl = "http://incentive"
    }

    private fun accountJson(accountId: UUID, ownerParty: UUID) =
        Response.ok("""{"id":"$accountId","partyId":"$ownerParty"}""").build()

    private fun termDepositProduct(id: UUID, public: Boolean = true, status: String = "ACTIVE"): String = """{
        "id":"$id", "code":"TERM_DEPOSIT_6M_CZK", "name":"Termínovaný vklad 6 měsíců",
        "type":"TERM_DEPOSIT", "currency":"CZK", "status":"$status", "isPublic":$public,
        "minBalance":10000, "termDepositConfig":{"termMonths":6,"interestRateAnnual":5.8,
        "payoutFrequency":"AT_MATURITY","autoRenewEnabled":true,"earlyWithdrawalPenaltyPct":50.0,
        "earlyWithdrawalNoticeDays":0}, "termsAndConditions":[]
    }
    """.trimIndent()

    private fun termDepositAccount(caller: UUID, product: UUID, openedAt: String): String = """{
        "id":"${UUID.randomUUID()}","partyId":"$caller","productId":"$product",
        "accountType":"TERM_DEPOSIT","status":"ACTIVE","openedAt":"$openedAt"
    }
    """.trimIndent()

    @Test
    fun `claimIncentive derives party and offer from trusted sources and forwards exact idempotency`() {
        val caller = UUID.randomUUID()
        val interactionRef = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val offerId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val forwarded = slot<String>()
        every { upstream.get("http://catalog/api/v1/products/$productId", caller.toString()) } returns
            Response.ok(termDepositProduct(productId)).build()
        every {
            upstream.get(
                "http://campaign/api/v1/campaigns/interactions/$interactionRef/attribution",
                caller.toString(),
            )
        } returns Response.ok(
            """{"campaignId":"${UUID.randomUUID()}","stepOrder":0,"channel":"PUSH","incentiveOfferRef":{"id":"$offerId","name":"WELCOME","version":1}}""",
        ).build()
        every {
            upstream.post(
                "http://incentive/api/v1/customer-incentives/offers/$offerId/reservations",
                caller.toString(),
                capture(forwarded),
                "claim-once",
            )
        } returns Response.status(201).entity("{\"status\":\"RESERVED\"}").build()

        val response = resourceFor(upstream, caller).claimIncentive(
            """{"interactionRef":"$interactionRef","code":"WELCOME10","productId":"$productId"}""",
            "claim-once",
        )

        assertThat(response.status).isEqualTo(201)
        val request = ObjectMapper().readTree(forwarded.captured)
        assertThat(request.path("productRef").asText()).isEqualTo(productId.toString())
        assertThat(request.path("attributionRef").asText()).isEqualTo(interactionRef.toString())
        assertThat(request.path("code").asText()).isEqualTo("WELCOME10")
        assertThat(request.has("partyRef")).isFalse()
        assertThat(request.has("offerId")).isFalse()
    }

    @Test
    fun `claimIncentive rejects client supplied party or offer identity before any upstream call`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val response = resourceFor(upstream, caller).claimIncentive(
            """{"interactionRef":"${UUID.randomUUID()}","code":"WELCOME10","productId":"${UUID.randomUUID()}","partyRef":"${UUID.randomUUID()}"}""",
            "claim-once",
        )

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.get(any(), any()) }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `claimIncentive rejects foreign interaction before calling Incentive Service`() {
        val caller = UUID.randomUUID()
        val interactionRef = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("http://catalog/api/v1/products/$productId", caller.toString()) } returns
            Response.ok(termDepositProduct(productId)).build()
        every {
            upstream.get(match { it.contains("/interactions/$interactionRef/attribution") }, caller.toString())
        } returns
            Response.status(404).build()

        val response = resourceFor(upstream, caller).claimIncentive(
            """{"interactionRef":"$interactionRef","code":"WELCOME10","productId":"$productId"}""",
            "claim-once",
        )

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(match { it.startsWith("http://incentive/") }, any(), any(), any()) }
    }

    @Test
    fun `getBalance rejects an account owned by another party and does not proxy (IDOR guard)`() {
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, other)
        val resp = resourceFor(upstream, caller).getBalance(acct)
        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.get(match { it.contains("/balances/") }, any()) }
    }

    @Test
    fun `getBalance proxies to balance-service when the account belongs to the caller`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, caller)
        every { upstream.get(match { it.contains("/balances/$acct") }, any()) } returns
            Response.ok("""[{"currency":"EUR","amount":"10.00"}]""").build()
        val resp = resourceFor(upstream, caller).getBalance(acct)
        assertThat(resp.status).isEqualTo(200)
        verify { upstream.get(match { it.contains("/balances/$acct") }, any()) }
    }

    @Test
    fun `getAccount rejects another party's account and serves the caller's own`() {
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$mine") }, any()) } returns accountJson(mine, caller)
        every { upstream.get(match { it.contains("/accounts/$theirs") }, any()) } returns accountJson(theirs, other)
        val r = resourceFor(upstream, caller)
        assertThat(r.getAccount(theirs).status).isEqualTo(403)
        assertThat(r.getAccount(mine).status).isEqualTo(200)
    }

    @Test
    fun `getBalance returns 403 (not 404) for a non-existent account — no existence oracle`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.status(404).build()
        assertThat(resourceFor(upstream, caller).getBalance(acct).status).isEqualTo(403)
    }

    @Test
    fun `term deposit catalogue exposes only public active offers`() {
        val caller = UUID.randomUUID()
        val visible = UUID.randomUUID()
        val private = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("type=TERM_DEPOSIT") }, any()) } returns Response.ok(
            "[${termDepositProduct(visible)},${termDepositProduct(private, public = false)}]",
        ).build()

        val response = resourceFor(upstream, caller).apply {
            productCatalogUrl = "http://catalog"
        }.listTermDepositOffers()

        assertThat(response.status).isEqualTo(200)
        val items = ObjectMapper().readTree(response.entity.toString()).path("items")
        assertThat(items).hasSize(1)
        assertThat(items[0].path("id").asText()).isEqualTo(visible.toString())
        assertThat(items[0].path("term").path("termMonths").asInt()).isEqualTo(6)
    }

    @Test
    fun `term deposit opening derives type and currency from the public offer`() {
        val caller = UUID.randomUUID()
        val product = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/products/$product") }, any()) } returns
            Response.ok(termDepositProduct(product)).build()
        every { upstream.get(match { it.contains("/parties/$caller") }, any()) } returns
            Response.ok("""{"status":"ACTIVE","legalName":"Ada Customer"}""").build()
        val sent = slot<String>()
        every { upstream.post(match { it.contains("/api/v1/accounts") }, any(), capture(sent), "retry-key") } returns
            Response.status(201).entity("""{"id":"${UUID.randomUUID()}"}""").build()

        val resource = resourceFor(upstream, caller).apply {
            productCatalogUrl = "http://catalog"
            partyServiceUrl = "http://party"
        }
        assertThat(
            resource.openTermDeposit(
                """{"productId":"$product","accountType":"CURRENT","currencyCode":"EUR"}""",
                "retry-key",
            ).status,
        ).isEqualTo(400)
        val response = resource.openTermDeposit("""{"productId":"$product"}""", "retry-key")

        assertThat(response.status).isEqualTo(201)
        val body = ObjectMapper().readTree(sent.captured)
        assertThat(body.path("accountType").asText()).isEqualTo("TERM_DEPOSIT")
        assertThat(body.path("currencyCode").asText()).isEqualTo("CZK")
        assertThat(body.path("legalName").asText()).isEqualTo("Ada Customer")
    }

    @Test
    fun `term deposit success commits the matching reservation with authoritative openedAt`() {
        val caller = UUID.randomUUID()
        val product = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        val openedAt = "2026-08-27T03:00:00Z"
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/products/$product") }, any()) } returns
            Response.ok(termDepositProduct(product)).build()
        every { upstream.get(match { it.contains("/parties/$caller") }, any()) } returns
            Response.ok("""{"status":"ACTIVE","legalName":"Ada Customer"}""").build()
        every { upstream.post("http://account/api/v1/accounts", caller.toString(), any(), "open-once") } returns
            Response.status(201).entity(termDepositAccount(caller, product, openedAt)).build()
        val commitBody = slot<String>()
        every {
            upstream.post(
                "http://incentive/api/v1/customer-incentives/reservations/$reservation/commit",
                caller.toString(),
                capture(commitBody),
                "open-once",
            )
        } returns Response.ok("""{"id":"$reservation","status":"COMMITTED"}""").build()

        val response = resourceFor(upstream, caller).apply {
            productCatalogUrl = "http://catalog"
            partyServiceUrl = "http://party"
        }.openTermDeposit(
            """{"productId":"$product","incentiveReservationId":"$reservation"}""",
            "open-once",
        )

        assertThat(response.status).isEqualTo(201)
        val responseJson = ObjectMapper().readTree(response.entity.toString())
        assertThat(responseJson.path("incentiveReservation").path("status").asText()).isEqualTo("COMMITTED")
        val evidence = ObjectMapper().readTree(commitBody.captured)
        assertThat(evidence.path("productRef").asText()).isEqualTo(product.toString())
        assertThat(evidence.path("qualifiedAt").asText()).isEqualTo(openedAt)
    }

    @Test
    fun `term deposit success without matching authoritative account evidence does not commit`() {
        val caller = UUID.randomUUID()
        val product = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/products/$product") }, any()) } returns
            Response.ok(termDepositProduct(product)).build()
        every { upstream.get(match { it.contains("/parties/$caller") }, any()) } returns
            Response.ok("""{"status":"ACTIVE","legalName":"Ada Customer"}""").build()
        every { upstream.post("http://account/api/v1/accounts", caller.toString(), any(), "mismatch") } returns
            Response.status(201).entity(
                termDepositAccount(caller, UUID.randomUUID(), "2026-08-27T03:00:00Z"),
            ).build()

        val response = resourceFor(upstream, caller).apply {
            productCatalogUrl = "http://catalog"
            partyServiceUrl = "http://party"
        }.openTermDeposit("""{"productId":"$product","incentiveReservationId":"$reservation"}""", "mismatch")

        assertThat(response.status).isEqualTo(502)
        verify(exactly = 0) {
            upstream.post(match { it.contains("/customer-incentives/reservations/") }, any(), any(), any())
        }
    }

    @Test
    fun `deterministic term deposit rejection releases the matching reservation`() {
        val caller = UUID.randomUUID()
        val product = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/products/$product") }, any()) } returns
            Response.ok(termDepositProduct(product)).build()
        every { upstream.get(match { it.contains("/parties/$caller") }, any()) } returns
            Response.ok("""{"status":"ACTIVE","legalName":"Ada Customer"}""").build()
        every { upstream.post("http://account/api/v1/accounts", caller.toString(), any(), "reject-once") } returns
            Response.status(422).entity("""{"error":"rejected"}""").build()
        every {
            upstream.post(
                "http://incentive/api/v1/customer-incentives/reservations/$reservation/release",
                caller.toString(),
                match { ObjectMapper().readTree(it).path("productRef").asText() == product.toString() },
                "reject-once",
            )
        } returns Response.ok("""{"id":"$reservation","status":"RELEASED"}""").build()

        val response = resourceFor(upstream, caller).apply {
            productCatalogUrl = "http://catalog"
            partyServiceUrl = "http://party"
        }.openTermDeposit(
            """{"productId":"$product","incentiveReservationId":"$reservation"}""",
            "reject-once",
        )

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `unknown auth routing and transient failures leave reservation retryable`() {
        listOf(401, 403, 404, 408, 409, 425, 429, 500, 502).forEach { status ->
            val caller = UUID.randomUUID()
            val product = UUID.randomUUID()
            val reservation = UUID.randomUUID()
            val upstream = mockk<UpstreamClient>()
            every { upstream.get(match { it.endsWith("/products/$product") }, any()) } returns
                Response.ok(termDepositProduct(product)).build()
            every { upstream.get(match { it.contains("/parties/$caller") }, any()) } returns
                Response.ok("""{"status":"ACTIVE","legalName":"Ada Customer"}""").build()
            every { upstream.post("http://account/api/v1/accounts", caller.toString(), any(), "retry-$status") } returns
                Response.status(status).entity("""{"error":"unavailable"}""").build()

            val response = resourceFor(upstream, caller).apply {
                productCatalogUrl = "http://catalog"
                partyServiceUrl = "http://party"
            }.openTermDeposit(
                """{"productId":"$product","incentiveReservationId":"$reservation"}""",
                "retry-$status",
            )

            assertThat(response.status).isEqualTo(status)
            verify(exactly = 0) {
                upstream.post(match { it.contains("/customer-incentives/reservations/") }, any(), any(), any())
            }
        }
    }

    // ── party_id claim resolution (B1 fix, ADR-0069 §2) ─────────────────────

    @Test
    fun `party_id claim is preferred over sub when both present`() {
        val partyId = UUID.randomUUID().toString()
        val keycloakUuid = UUID.randomUUID().toString()
        val resolved = CustomerEdgeResource.resolvePartyIdClaim(
            partyIdClaim = partyId,
            sub = keycloakUuid,
        )
        assertThat(resolved).isEqualTo(partyId)
    }

    @Test
    fun `falls back to sub when party_id claim is absent`() {
        val keycloakUuid = UUID.randomUUID().toString()
        val resolved = CustomerEdgeResource.resolvePartyIdClaim(
            partyIdClaim = null,
            sub = keycloakUuid,
        )
        assertThat(resolved).isEqualTo(keycloakUuid)
    }

    @Test
    fun `falls back to sub when party_id claim is blank`() {
        val keycloakUuid = UUID.randomUUID().toString()
        val resolved = CustomerEdgeResource.resolvePartyIdClaim(
            partyIdClaim = "  ",
            sub = keycloakUuid,
        )
        assertThat(resolved).isEqualTo(keycloakUuid)
    }

    @Test
    fun `returns null when both party_id and sub are absent`() {
        val resolved = CustomerEdgeResource.resolvePartyIdClaim(
            partyIdClaim = null,
            sub = null,
        )
        assertThat(resolved).isNull()
    }

    @Test
    fun `returns null when both party_id and sub are blank`() {
        val resolved = CustomerEdgeResource.resolvePartyIdClaim(
            partyIdClaim = "",
            sub = "   ",
        )
        assertThat(resolved).isNull()
    }

    // ── transaction-read ownership: account-owner extraction (IDOR guard) ────────

    @Test
    fun `extracts the owning partyId from an account payload`() {
        val party = "8044e05a-a93e-4403-a7db-1a7adf01f298"
        val body = """{"id":"6c2c9c26-5b26-422d-b618-6c407f548d0d","accountNumber":"CZ09...",""" +
            """"partyId":"$party","currencyCode":"CZK","status":"ACTIVE"}"""
        assertThat(CustomerEdgeResource.extractOwnerPartyId(body)).isEqualTo(party)
    }

    @Test
    fun `owner extraction returns null when no partyId field is present`() {
        val body = """{"error":"not found"}"""
        assertThat(CustomerEdgeResource.extractOwnerPartyId(body)).isNull()
    }

    @Test
    fun `extracted owner not matching the caller is rejected (IDOR guard)`() {
        val caller = UUID.randomUUID().toString()
        val otherOwner = UUID.randomUUID().toString()
        val body = """{"id":"${UUID.randomUUID()}","partyId":"$otherOwner","status":"ACTIVE"}"""
        // The edge compares this against the caller's party; a mismatch must NOT be treated as owned.
        assertThat(CustomerEdgeResource.extractOwnerPartyId(body)).isNotEqualTo(caller)
    }

    // ── payment-initiation ownership: debtor account parsing (IDOR bypass guard) ──

    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    @Test
    fun `parses the debtorAccountId from a payment-initiation body`() {
        val debtor = "6c2c9c26-5b26-422d-b618-6c407f548d0d"
        val body = """{"debtorAccountId":"$debtor","amount":250.00,"currency":"CZK","creditorName":"Alice"}"""
        assertThat(CustomerEdgeResource.parseDebtorAccountId(mapper, body)).isEqualTo(debtor)
    }

    @Test
    fun `parse returns null when debtorAccountId is absent (rejected by the edge)`() {
        val body = """{"amount":250.00,"currency":"CZK","creditorName":"Alice"}"""
        assertThat(CustomerEdgeResource.parseDebtorAccountId(mapper, body)).isNull()
    }

    @Test
    fun `a duplicate debtorAccountId resolves to the LAST value, matching the upstream parser`() {
        // Closes the IDOR bypass: the edge must ownership-check the same value the upstream uses.
        val own = "6c2c9c26-5b26-422d-b618-6c407f548d0d"
        val victim = "11111111-2222-3333-4444-555555555555"
        val body = """{"debtorAccountId":"$own","amount":1,"currency":"CZK","debtorAccountId":"$victim"}"""
        assertThat(CustomerEdgeResource.parseDebtorAccountId(mapper, body)).isEqualTo(victim)
    }

    @Test
    fun `injectField overwrites a field and keeps nested objects valid`() {
        val jwtParty = "8044e05a-a93e-4403-a7db-1a7adf01f298"
        // Body with a NESTED object (dynamicLinkingData) and a spoofed partyId.
        val body = """{"purpose":"PAYMENT_INITIATION","partyId":"spoofed",""" +
            """"dynamicLinkingData":{"amount":"250.00","currency":"CZK"}}"""
        val out = CustomerEdgeResource.injectField(mapper, body, "partyId", jwtParty)!!
        val node = mapper.readTree(out)
        assertThat(node.get("partyId").asText()).isEqualTo(jwtParty) // JWT value wins
        assertThat(node.get("dynamicLinkingData").get("amount").asText()).isEqualTo("250.00") // nested preserved
    }

    @Test
    fun `device registration partyId injection keeps a nested-object body valid JSON`() {
        // registerDevice (POST /devices): the OLD string-surgery (body.trimEnd('}') + ...) corrupted a
        // body whose LAST field is a nested object — it appended after the inner brace, producing
        // invalid JSON. Jackson injection must preserve the nesting and overwrite any client partyId.
        val jwtParty = "8044e05a-a93e-4403-a7db-1a7adf01f298"
        val body = """{"platform":"FCM","token":"abc123","partyId":"spoofed",""" +
            """"clientMeta":{"appVersion":"1.2.0","osVersion":"iOS 17.4"}}"""
        val out = CustomerEdgeResource.injectField(mapper, body, "partyId", jwtParty)!!
        val node = mapper.readTree(out)
        assertThat(node.get("partyId").asText()).isEqualTo(jwtParty) // JWT value wins
        assertThat(node.get("token").asText()).isEqualTo("abc123") // scalars preserved
        assertThat(node.get("clientMeta").get("osVersion").asText()).isEqualTo("iOS 17.4") // nested preserved
    }

    @Test
    fun `onboarding account partyId injection keeps a nested-object body valid JSON`() {
        // openAccount (POST /onboarding/account): same corruption class — a body ending in a nested
        // object (e.g. accountPreferences) would be mangled by string surgery. partyId comes from the
        // JWT, never the body (IDOR prevention), so a client-supplied value must be overwritten.
        val jwtParty = "8044e05a-a93e-4403-a7db-1a7adf01f298"
        val body = """{"productId":"CURRENT_EUR","accountType":"CURRENT","partyId":"spoofed",""" +
            """"accountPreferences":{"statementChannel":"PUSH","currencyCode":"EUR"}}"""
        val out = CustomerEdgeResource.injectField(mapper, body, "partyId", jwtParty)!!
        val node = mapper.readTree(out)
        assertThat(node.get("partyId").asText()).isEqualTo(jwtParty) // JWT value wins
        assertThat(node.get("accountType").asText()).isEqualTo("CURRENT") // scalars preserved
        assertThat(node.get("accountPreferences").get("currencyCode").asText()).isEqualTo("EUR") // nested preserved
    }

    @Test
    fun `injectField returns null on a malformed (non-object) body so the route can reject it`() {
        // Both routes treat a null from injectField as a malformed body and reject (4xx) rather than
        // forwarding a corrupt payload upstream.
        assertThat(CustomerEdgeResource.injectField(mapper, "not json", "partyId", "x")).isNull()
        assertThat(CustomerEdgeResource.injectField(mapper, """["a","b"]""", "partyId", "x")).isNull()
    }

    // ── in-app engagement surfaces ────────────────────────────────────────────────────────────

    @Test
    fun `recordSurfaceEvent overwrites a spoofed party id with the JWT party`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val body = slot<String>()
        every {
            upstream.post(match { it.endsWith("/api/v1/surfaces/events") }, caller.toString(), capture(body), any())
        } returns
            Response.status(Response.Status.ACCEPTED).build()

        val response = resourceFor(upstream, caller).recordSurfaceEvent(
            """{"partyId":"${UUID.randomUUID()}","contentId":"SAVINGS_RATE_BANNER","slot":"HOME_BANNER","type":"CLICK"}""",
        )

        assertThat(response.status).isEqualTo(Response.Status.ACCEPTED.statusCode)
        assertThat(mapper.readTree(body.captured).path("partyId").asText()).isEqualTo(caller.toString())
        assertThat(mapper.readTree(body.captured).path("contentId").asText()).isEqualTo("SAVINGS_RATE_BANNER")
    }

    @Test
    fun `recordSurfaceEvent validates an opaque interaction reference for the caller before forwarding`() {
        val caller = UUID.randomUUID()
        val interactionRef = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val forwarded = slot<String>()
        every {
            upstream.get(
                "http://campaign/api/v1/campaigns/interactions/$interactionRef/attribution",
                caller.toString(),
            )
        } returns Response.ok(
            """{"campaignId":"$campaignId","stepOrder":0,"channel":"PUSH"}""",
        ).build()
        every { upstream.post(any(), caller.toString(), capture(forwarded), any()) } returns
            Response.status(202).build()

        val response = resourceFor(upstream, caller).recordSurfaceEvent(
            """{"contentId":"SAVINGS_RATE_BANNER","slot":"HOME_BANNER","type":"CLICK","interactionRef":"$interactionRef"}""",
        )

        assertThat(response.status).isEqualTo(202)
        verify(exactly = 1) {
            upstream.get(
                "http://campaign/api/v1/campaigns/interactions/$interactionRef/attribution",
                caller.toString(),
            )
        }
        verify(exactly = 1) { upstream.post(any(), caller.toString(), any(), any()) }
        assertThat(mapper.readTree(forwarded.captured).path("campaignId").asText()).isEqualTo(campaignId.toString())
        assertThat(mapper.readTree(forwarded.captured).path("stepOrder").asInt()).isEqualTo(0)
        assertThat(mapper.readTree(forwarded.captured).path("channel").asText()).isEqualTo("PUSH")
    }

    @Test
    fun `recordSurfaceEvent strips client supplied campaign attribution without a validated reference`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val forwarded = slot<String>()
        every { upstream.post(any(), caller.toString(), capture(forwarded), any()) } returns
            Response.status(202).build()

        resourceFor(upstream, caller).recordSurfaceEvent(
            """{"contentId":"SAVINGS_RATE_BANNER","slot":"HOME_BANNER","type":"CLICK","campaignId":"${UUID.randomUUID()}","stepOrder":99,"channel":"PUSH"}""",
        )

        val node = mapper.readTree(forwarded.captured)
        assertThat(node.has("campaignId")).isFalse()
        assertThat(node.has("stepOrder")).isFalse()
        assertThat(node.has("channel")).isFalse()
    }

    @Test
    fun `recordSurfaceEvent rejects another party interaction reference without forwarding`() {
        val caller = UUID.randomUUID()
        val interactionRef = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), caller.toString()) } returns Response.status(404).build()

        val response = resourceFor(upstream, caller).recordSurfaceEvent(
            """{"contentId":"SAVINGS_RATE_BANNER","slot":"HOME_BANNER","type":"CLICK","interactionRef":"$interactionRef"}""",
        )

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `recordSurfaceEvent rejects a client claimed conversion without forwarding`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()

        val response = resourceFor(upstream, caller).recordSurfaceEvent(
            """{"contentId":"SAVINGS_RATE_BANNER","slot":"HOME_BANNER","type":"CONVERSION"}""",
        )

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `getSurface rejects a non-catalogue slot before it calls the upstream`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()

        val response = resourceFor(upstream, caller).getSurface("../../not-a-slot")

        assertThat(response.status).isEqualTo(Response.Status.BAD_REQUEST.statusCode)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    // ── statement render: currency + format allow-lists (deny-by-default) ────────

    @Test
    fun `valid 3-letter uppercase currency passes the render guard`() {
        assertThat(CustomerEdgeResource.isValidCurrency("CZK")).isTrue()
        assertThat(CustomerEdgeResource.isValidCurrency("EUR")).isTrue()
    }

    @Test
    fun `malformed currency is rejected (blocks path injection)`() {
        assertThat(CustomerEdgeResource.isValidCurrency("czk")).isFalse() // lowercase
        assertThat(CustomerEdgeResource.isValidCurrency("CZ")).isFalse() // too short
        assertThat(CustomerEdgeResource.isValidCurrency("CZKK")).isFalse() // too long
        assertThat(CustomerEdgeResource.isValidCurrency("../")).isFalse() // traversal attempt
        assertThat(CustomerEdgeResource.isValidCurrency("C K")).isFalse() // space
    }

    @Test
    fun `format allow-list normalises known formats and defaults blank to PDF`() {
        assertThat(CustomerEdgeResource.normalizeStatementFormat(null)).isEqualTo("PDF")
        assertThat(CustomerEdgeResource.normalizeStatementFormat("")).isEqualTo("PDF")
        assertThat(CustomerEdgeResource.normalizeStatementFormat("pdf")).isEqualTo("PDF")
        assertThat(CustomerEdgeResource.normalizeStatementFormat("camt_053")).isEqualTo("CAMT_053")
        assertThat(CustomerEdgeResource.normalizeStatementFormat("camt053")).isEqualTo("CAMT_053")
        assertThat(CustomerEdgeResource.normalizeStatementFormat(" MT940 ")).isEqualTo("MT940")
    }

    @Test
    fun `format allow-list rejects anything unknown (route returns 400)`() {
        assertThat(CustomerEdgeResource.normalizeStatementFormat("xml")).isNull()
        assertThat(CustomerEdgeResource.normalizeStatementFormat("../etc/passwd")).isNull()
        assertThat(CustomerEdgeResource.normalizeStatementFormat("PDF;rm -rf")).isNull()
    }

    // ── domestic-payment enrichment: IBAN→BBAN, creditor parse, request build ────

    @Test
    fun `czech IBAN splits into account number and bank code`() {
        // CZ65 0800 0000 1920 0014 5399 → bank 0800, prefix 000019 (→19), base 2000145399
        val parsed = CustomerEdgeResource.czechIbanToBban("CZ6508000000192000145399")
        assertThat(parsed).isEqualTo("19-2000145399" to "0800")
    }

    @Test
    fun `czech IBAN with zero prefix drops the prefix`() {
        // bank 2010, prefix 000000 → dropped, base 0000200145 → 200145
        val parsed = CustomerEdgeResource.czechIbanToBban("CZ6520100000000000200145")
        assertThat(parsed?.second).isEqualTo("2010")
        assertThat(parsed?.first).doesNotContain("-") // no prefix segment
    }

    @Test
    fun `non-czech or malformed IBAN is rejected`() {
        assertThat(CustomerEdgeResource.czechIbanToBban("DE89370400440532013000")).isNull() // not CZ
        assertThat(CustomerEdgeResource.czechIbanToBban("CZ6508")).isNull() // too short
        assertThat(CustomerEdgeResource.czechIbanToBban("CZXX08000000192000145399")).isNull() // non-digit
    }

    @Test
    fun `creditor account parses number and 4-digit bank code`() {
        assertThat(CustomerEdgeResource.parseCreditorAccount("2000145399/0800")).isEqualTo("2000145399" to "0800")
        assertThat(CustomerEdgeResource.parseCreditorAccount(" 19-2000145399 / 0800 ")).isEqualTo(
            "19-2000145399" to "0800",
        )
    }

    @Test
    fun `malformed creditor account is rejected`() {
        assertThat(CustomerEdgeResource.parseCreditorAccount("2000145399")).isNull() // no bank
        assertThat(CustomerEdgeResource.parseCreditorAccount("2000145399/080")).isNull() // bank not 4 digits
        assertThat(CustomerEdgeResource.parseCreditorAccount("../etc/0800")).isNull() // injection-ish
        assertThat(CustomerEdgeResource.parseCreditorAccount("2000145399/08AB")).isNull() // non-digit bank
    }

    @Test
    fun `creditor account accepts Czech IBAN as fallback`() {
        // IBAN CZ6508000000192000145399 → 19-2000145399/0800
        assertThat(CustomerEdgeResource.czechIbanToBban("CZ6508000000192000145399"))
            .isEqualTo("19-2000145399" to "0800")
        // IBAN-only string that parseCreditorAccount rejects → czechIbanToBban kicks in
        assertThat(CustomerEdgeResource.parseCreditorAccount("CZ6508000000192000145399")).isNull()
        assertThat(CustomerEdgeResource.czechIbanToBban("CZ6508000000192000145399")).isNotNull()
    }

    @Test
    fun `buildDomesticRequest enriches the lightweight body into the full instruction`() {
        val app = """{"debtorAccountId":"ignored","amount":"250.00","currency":"CZK",""" +
            """"creditorAccountNumber":"2000145399/0800","creditorName":"Alice Novak",""" +
            """"variableSymbol":"1234567890","reference":"Faktura 5"}"""
        val out = CustomerEdgeResource.buildDomesticRequest(
            mapper,
            app,
            "6c2c9c26-5b26-422d-b618-6c407f548d0d",
            "19-2000145399",
            "2010",
            "Bob Dluznik",
            "2000145399",
            "0800",
        )!!
        val node = mapper.readTree(out)
        assertThat(node.get("debtorAccountNumber").asText()).isEqualTo("19-2000145399")
        assertThat(node.get("debtorBankCode").asText()).isEqualTo("2010")
        assertThat(node.get("debtorName").asText()).isEqualTo("Bob Dluznik")
        assertThat(node.get("creditorAccountNumber").asText()).isEqualTo("2000145399")
        assertThat(node.get("creditorBankCode").asText()).isEqualTo("0800")
        assertThat(node.get("creditorName").asText()).isEqualTo("Alice Novak")
        assertThat(node.get("amount").decimalValue()).isEqualByComparingTo(java.math.BigDecimal("250.00"))
        assertThat(node.get("amount").isNumber).isTrue() // emitted as a JSON number, not a string
        assertThat(node.get("variableSymbol").asText()).isEqualTo("1234567890")
        assertThat(node.get("messageForPayee").asText()).isEqualTo("Faktura 5")
        assertThat(node.get("priority").asText()).isEqualTo("STANDARD")
        assertThat(node.has("specificSymbol")).isFalse() // absent optional not emitted
    }

    @Test
    fun `buildDomesticRequest returns null when a required field is missing`() {
        assertThat(
            CustomerEdgeResource.buildDomesticRequest(
                mapper,
                """{"creditorName":"A"}""",
                "d",
                "da",
                "db",
                "dn",
                "ca",
                "cb",
            ),
        ).isNull() // no amount
        assertThat(
            CustomerEdgeResource.buildDomesticRequest(
                mapper,
                """{"amount":"10.00"}""",
                "d",
                "da",
                "db",
                "dn",
                "ca",
                "cb",
            ),
        ).isNull() // no creditorName
    }

    @Test
    fun `buildDomesticRequest forwards priority from body (URGENT, INSTANT, unknown defaults to STANDARD)`() {
        fun build(priorityField: String?) = CustomerEdgeResource.buildDomesticRequest(
            mapper,
            """{"amount":"10.00","creditorName":"A"${priorityField?.let { ""","priority":"$it"""" } ?: ""}}""",
            "d",
            "da",
            "db",
            "dn",
            "ca",
            "cb",
        )!!.let { mapper.readTree(it).get("priority").asText() }

        assertThat(build("URGENT")).isEqualTo("URGENT")
        assertThat(build("INSTANT")).isEqualTo("INSTANT")
        assertThat(build("STANDARD")).isEqualTo("STANDARD")
        assertThat(build(null)).isEqualTo("STANDARD") // absent → default
        assertThat(build("INVALID")).isEqualTo("STANDARD") // unknown → default
    }

    @Test
    fun `buildSepaRequest enriches the lightweight body (IBAN-native, type SCT)`() {
        val app = """{"debtorAccountId":"ignored","amount":"120.00","currency":"EUR",""" +
            """"creditorIban":"DE89370400440532013000","creditorName":"Hans Muller",""" +
            """"creditorBic":"COBADEFFXXX","reference":"Invoice 42"}"""
        val out = CustomerEdgeResource.buildSepaRequest(
            mapper,
            app,
            "6c2c9c26-5b26-422d-b618-6c407f548d0d",
            "CZ6508000000192000145399",
            "Bob Platce",
        )!!
        val node = mapper.readTree(out)
        assertThat(node.get("type").asText()).isEqualTo("SCT")
        assertThat(node.get("debtorIban").asText()).isEqualTo("CZ6508000000192000145399")
        assertThat(node.get("debtorName").asText()).isEqualTo("Bob Platce")
        assertThat(node.get("creditorIban").asText()).isEqualTo("DE89370400440532013000")
        assertThat(node.get("creditorName").asText()).isEqualTo("Hans Muller")
        assertThat(node.get("creditorBic").asText()).isEqualTo("COBADEFFXXX")
        assertThat(node.get("amount").decimalValue()).isEqualByComparingTo(java.math.BigDecimal("120.00"))
        assertThat(node.get("amount").isNumber).isTrue()
        assertThat(node.get("currency").asText()).isEqualTo("EUR")
        assertThat(node.get("remittanceInfo").asText()).isEqualTo("Invoice 42")
    }

    @Test
    fun `buildSepaRequest returns null when creditorIban or amount is missing`() {
        assertThat(
            CustomerEdgeResource.buildSepaRequest(
                mapper,
                """{"amount":"10.00","creditorName":"X"}""",
                "d",
                "di",
                "dn",
            ),
        ).isNull() // no creditorIban
        assertThat(
            CustomerEdgeResource.buildSepaRequest(
                mapper,
                """{"creditorIban":"DE89","creditorName":"X"}""",
                "d",
                "di",
                "dn",
            ),
        ).isNull() // no amount
    }

    // ── upstream query building: cursor injection guard ─────────────────────────

    @Test
    fun `query carries the verified accountId, limit and an absent cursor`() {
        val accountId = UUID.randomUUID()
        val q = CustomerEdgeResource.buildTransactionsQuery(accountId, 20, null)
        assertThat(q).isEqualTo("?accountId=$accountId&limit=20")
    }

    @Test
    fun `a cursor that tries to inject a second accountId is URL-encoded, not appended raw`() {
        val accountId = UUID.randomUUID()
        val victim = UUID.randomUUID()
        val q = CustomerEdgeResource.buildTransactionsQuery(accountId, 20, "abc&accountId=$victim")
        // Exactly one accountId param (the verified one); the injected one is encoded into the cursor.
        assertThat(q.split("accountId=")).hasSize(2)
        assertThat(q).doesNotContain("&accountId=$victim")
        assertThat(q).contains("%26accountId%3D") // & and = encoded
    }

    // ── own-account transfers (POST /transfers) ─────────────────────────────────

    private fun transferResourceFor(upstream: UpstreamClient, callerParty: UUID): CustomerEdgeResource =
        resourceFor(upstream, callerParty).apply {
            transactionServiceUrl = "http://tx"
        }

    private fun transferBody(src: UUID, dst: UUID, amount: String = "250.00") =
        """{"sourceAccountId":"$src","targetAccountId":"$dst","amount":"$amount","currency":"CZK"}"""

    @Test
    fun `createTransfer rejects a source account owned by another party (IDOR guard)`() {
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val src = UUID.randomUUID()
        val dst = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$src") }, any()) } returns accountJson(src, other)
        val resp = transferResourceFor(upstream, caller).createTransfer(transferBody(src, dst), null)
        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(any(), any(), any()) }
    }

    @Test
    fun `createTransfer rejects a target account owned by another party (no cross-party deposits)`() {
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val src = UUID.randomUUID()
        val dst = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$src") }, any()) } returns accountJson(src, caller)
        every { upstream.get(match { it.contains("/accounts/$dst") }, any()) } returns accountJson(dst, other)
        val resp = transferResourceFor(upstream, caller).createTransfer(transferBody(src, dst), null)
        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(any(), any(), any()) }
    }

    @Test
    fun `createTransfer rejects identical source and target`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val resp = transferResourceFor(upstream, caller).createTransfer(transferBody(acct, acct), null)
        assertThat(resp.status).isEqualTo(400)
    }

    @Test
    fun `createTransfer forwards a TRANSFER saga with the caller's idempotency key when both legs are owned`() {
        val caller = UUID.randomUUID()
        val src = UUID.randomUUID()
        val dst = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$src") }, any()) } returns accountJson(src, caller)
        every { upstream.get(match { it.contains("/accounts/$dst") }, any()) } returns accountJson(dst, caller)
        var forwarded: String? = null
        every { upstream.post(match { it.contains("/api/v1/transactions") }, any(), any()) } answers {
            forwarded = thirdArg()
            Response.status(201).entity("""{"id":"${UUID.randomUUID()}","status":"COMPLETED"}""").build()
        }

        val resp = transferResourceFor(upstream, caller)
            .createTransfer(transferBody(src, dst, amount = "1500.50"), "app-key-1")

        assertThat(resp.status).isEqualTo(201)
        val node = mapper.readTree(forwarded!!)
        assertThat(node.get("type").asText()).isEqualTo("TRANSFER")
        assertThat(node.get("sourceAccountId").asText()).isEqualTo(src.toString())
        assertThat(node.get("targetAccountId").asText()).isEqualTo(dst.toString())
        assertThat(node.get("amount").decimalValue()).isEqualByComparingTo(java.math.BigDecimal("1500.50"))
        assertThat(node.get("currencyCode").asText()).isEqualTo("CZK")
        assertThat(node.get("idempotencyKey").asText()).isEqualTo("app-key-1")
        assertThat(node.get("valueDate").asText()).isNotBlank()
    }

    @Test
    fun `parseTransferRequest rejects non-positive amounts and malformed ids`() {
        val src = UUID.randomUUID()
        val dst = UUID.randomUUID()
        assertThat(CustomerEdgeResource.parseTransferRequest(mapper, transferBody(src, dst, amount = "0"))).isNull()
        assertThat(CustomerEdgeResource.parseTransferRequest(mapper, transferBody(src, dst, amount = "-5"))).isNull()
        assertThat(
            CustomerEdgeResource.parseTransferRequest(
                mapper,
                """{"sourceAccountId":"nonsense","targetAccountId":"$dst","amount":"10"}""",
            ),
        ).isNull()
        assertThat(CustomerEdgeResource.parseTransferRequest(mapper, "not json")).isNull()
        assertThat(CustomerEdgeResource.parseTransferRequest(mapper, transferBody(src, dst))).isNotNull()
    }

    // ── SCA settlement gate (ADR-0021): payments only behind a consumed challenge ──

    private fun paymentResourceFor(upstream: UpstreamClient, callerParty: UUID): CustomerEdgeResource =
        resourceFor(upstream, callerParty).apply {
            domesticPaymentServiceUrl = "http://dompay"
            scaServiceUrl = "http://sca"
            partyServiceUrl = "http://party"
        }

    private fun domesticBody(debtor: UUID) = """{"debtorAccountId":"$debtor","amount":"250.00","currency":"CZK",""" +
        """"creditorAccountNumber":"2000145399/0800","creditorName":"Alice"}"""

    private fun stubOwnedCzAccount(upstream: UpstreamClient, caller: UUID, acct: UUID) {
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns Response.ok(
            """{"id":"$acct","partyId":"$caller","accountNumber":"CZ6508000000192000145399"}""",
        ).build()
        every { upstream.get(match { it.contains("/parties/$caller") }, any()) } returns Response.ok(
            """{"id":"$caller","legalName":"Jan Novák"}""",
        ).build()
    }

    @Test
    fun `a domestic payment without an SCA challenge is refused and never reaches the payment rail`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubOwnedCzAccount(upstream, caller, acct)

        val resp = paymentResourceFor(upstream, caller).createDomesticPayment(domesticBody(acct), null, null)

        assertThat(resp.status).isEqualTo(403)
        assertThat(resp.entity.toString()).contains("SCA_REQUIRED")
        verify(exactly = 0) { upstream.post(match { it.contains("dompay") }, any(), any(), any()) }
    }

    @Test
    fun `a domestic payment forwards only after sca-service consumes the challenge for THIS operation`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val challengeId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubOwnedCzAccount(upstream, caller, acct)
        var consumeBody: String? = null
        every { upstream.post(match { it.contains("/challenges/$challengeId/consume") }, any(), any()) } answers {
            consumeBody = thirdArg()
            Response.ok("""{"id":"$challengeId","status":"COMPLETED"}""").build()
        }
        every { upstream.post(match { it.contains("dompay") }, any(), any(), any()) } returns
            Response.status(201).entity("""{"id":"${UUID.randomUUID()}","status":"RECEIVED"}""").build()

        val resp = paymentResourceFor(upstream, caller)
            .createDomesticPayment(domesticBody(acct), "key-1", challengeId.toString())

        assertThat(resp.status).isEqualTo(201)
        // The consume carries exactly the operation being executed (dynamic linking input).
        val node = mapper.readTree(consumeBody!!)
        assertThat(node.get("partyId").asText()).isEqualTo(caller.toString())
        assertThat(node.get("amount").asText()).isEqualTo("250.00")
        assertThat(node.get("currency").asText()).isEqualTo("CZK")
        assertThat(node.get("creditor").asText()).isEqualTo("2000145399/0800")
        verify(exactly = 1) { upstream.post(match { it.contains("dompay") }, any(), any(), any()) }
    }

    @Test
    fun `a rejected consume (replay, mismatch, not approved) blocks the payment`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val challengeId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubOwnedCzAccount(upstream, caller, acct)
        every { upstream.post(match { it.contains("/consume") }, any(), any()) } returns
            Response.status(409).entity("""{"code":"VALIDATION_ERROR","message":"already consumed"}""").build()

        val resp = paymentResourceFor(upstream, caller)
            .createDomesticPayment(domesticBody(acct), null, challengeId.toString())

        assertThat(resp.status).isEqualTo(403)
        assertThat(resp.entity.toString()).contains("SCA_REJECTED")
        verify(exactly = 0) { upstream.post(match { it.contains("dompay") }, any(), any(), any()) }
    }

    @Test
    fun `createTransfer threads the customer identity and the RTS Art 15 exemption to the saga`() {
        val caller = UUID.randomUUID()
        val src = UUID.randomUUID()
        val dst = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$src") }, any()) } returns accountJson(src, caller)
        every { upstream.get(match { it.contains("/accounts/$dst") }, any()) } returns accountJson(dst, caller)
        var forwarded: String? = null
        every { upstream.post(match { it.contains("/api/v1/transactions") }, any(), any()) } answers {
            forwarded = thirdArg()
            Response.status(201).entity("""{"id":"${UUID.randomUUID()}","status":"COMPLETED"}""").build()
        }

        transferResourceFor(upstream, caller).createTransfer(transferBody(src, dst), null)

        val node = mapper.readTree(forwarded!!)
        assertThat(node.get("initiatedByPartyId").asText()).isEqualTo(caller.toString())
        assertThat(node.get("scaExemption").asText()).isEqualTo("PSD2_RTS_ART15_OWN_ACCOUNT")
    }

    // ── domestic-payment status read-path + settlement reconciliation (ADR-0108) ──

    private fun statusResourceFor(
        upstream: UpstreamClient,
        callerParty: UUID,
        store: PaymentSessionStore = PaymentSessionStore(),
    ): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        store,
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns callerParty.toString()
            every { subject } returns callerParty.toString()
        }
        objectMapper = ObjectMapper()
        accountServiceUrl = "http://account"
        domesticPaymentServiceUrl = "http://dompay"
        sepaPaymentServiceUrl = "http://sepa"
    }

    private fun paymentJson(paymentId: UUID, debtor: UUID, status: String) =
        Response.ok("""{"id":"$paymentId","status":"$status","debtorAccountId":"$debtor"}""").build()

    @Test
    fun `getDomesticPaymentStatus returns the status for the caller's own payment`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/domestic-payments/$pid") }, any()) } returns
            paymentJson(pid, acct, "SETTLED")
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, caller)
        val resp = statusResourceFor(upstream, caller).getDomesticPaymentStatus(pid.toString())
        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity.toString()).contains(""""status":"SETTLED"""")
    }

    @Test
    fun `getDomesticPaymentStatus hides another party's payment behind a 404 (IDOR guard)`() {
        // The payment exists upstream but its debtor account is owned by someone else: the edge must
        // not confirm its existence (no probing oracle), so it collapses to the same 404 as a miss.
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/domestic-payments/$pid") }, any()) } returns
            paymentJson(pid, acct, "SETTLED")
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, other)
        val resp = statusResourceFor(upstream, caller).getDomesticPaymentStatus(pid.toString())
        assertThat(resp.status).isEqualTo(404)
    }

    @Test
    fun `getDomesticPaymentStatus returns 404 for a malformed id and for an upstream miss`() {
        val caller = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>(relaxed = true)
        every { upstream.get(any(), any()) } returns Response.status(404).build()
        val r = statusResourceFor(upstream, caller)
        assertThat(r.getDomesticPaymentStatus("not-a-uuid").status).isEqualTo(404)
        assertThat(r.getDomesticPaymentStatus(pid.toString()).status).isEqualTo(404)
    }

    @Test
    fun `getSepaPaymentStatus returns the status for the caller's own payment`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("http://sepa/api/v1/sepa-payments/$pid") }, any()) } returns
            paymentJson(pid, acct, "COMPLETED")
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, caller)
        val resp = statusResourceFor(upstream, caller).getSepaPaymentStatus(pid.toString())
        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity.toString()).contains(""""status":"COMPLETED"""")
    }

    @Test
    fun `getSepaPaymentStatus hides another party's payment behind a 404 (IDOR guard)`() {
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/sepa-payments/$pid") }, any()) } returns
            paymentJson(pid, acct, "COMPLETED")
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, other)
        val resp = statusResourceFor(upstream, caller).getSepaPaymentStatus(pid.toString())
        assertThat(resp.status).isEqualTo(404)
    }

    @Test
    fun `getSepaPaymentStatus returns 404 for a malformed id and for an upstream miss`() {
        val caller = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>(relaxed = true)
        every { upstream.get(any(), any()) } returns Response.status(404).build()
        val r = statusResourceFor(upstream, caller)
        assertThat(r.getSepaPaymentStatus("not-a-uuid").status).isEqualTo(404)
        assertThat(r.getSepaPaymentStatus(pid.toString()).status).isEqualTo(404)
    }

    @Test
    fun `session status is ACTIVE before any payer has initiated (no upstream call)`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val store = PaymentSessionStore()
        val token = store.create(acct.toString(), caller.toString(), "Jan", "250", "CZ…5399")
        val upstream = mockk<UpstreamClient>(relaxed = true)
        val resp = statusResourceFor(upstream, caller, store).paymentSessionStatus(token)
        assertThat(resp.entity.toString()).contains(""""status":"ACTIVE"""")
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `session status is PROCESSING while the payer's payment is accepted but not settled`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val store = PaymentSessionStore()
        val token = store.create(acct.toString(), caller.toString(), "Jan", "250", "CZ…5399")
        store.attachPayment(token, pid.toString())
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/domestic-payments/$pid") }, any()) } returns
            paymentJson(pid, acct, "SENT_TO_CLEARING")
        val resp = statusResourceFor(upstream, caller, store).paymentSessionStatus(token)
        assertThat(resp.entity.toString()).contains(""""status":"PROCESSING"""")
    }

    @Test
    fun `session status flips to PAID once the payment SETTLES, and stays PAID (sticky)`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val store = PaymentSessionStore()
        val token = store.create(acct.toString(), caller.toString(), "Jan", "250", "CZ…5399")
        store.attachPayment(token, pid.toString())
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/domestic-payments/$pid") }, any()) } returns
            paymentJson(pid, acct, "SETTLED")
        val r = statusResourceFor(upstream, caller, store)
        assertThat(r.paymentSessionStatus(token).entity.toString()).contains(""""status":"PAID"""")
        // Sticky: even an upstream blip afterwards cannot un-pay it (and no read is needed once paid).
        every { upstream.get(any(), any()) } returns Response.status(502).build()
        assertThat(r.paymentSessionStatus(token).entity.toString()).contains(""""status":"PAID"""")
    }

    @Test
    fun `session status surfaces a terminal payment failure as REJECTED`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val store = PaymentSessionStore()
        val token = store.create(acct.toString(), caller.toString(), "Jan", "250", "CZ…5399")
        store.attachPayment(token, pid.toString())
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/domestic-payments/$pid") }, any()) } returns
            paymentJson(pid, acct, "RETURNED")
        val resp = statusResourceFor(upstream, caller, store).paymentSessionStatus(token)
        assertThat(resp.entity.toString()).contains(""""status":"REJECTED"""")
    }

    @Test
    fun `session status stays PROCESSING (never falsely PAID) when the upstream read fails`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val pid = UUID.randomUUID()
        val store = PaymentSessionStore()
        val token = store.create(acct.toString(), caller.toString(), "Jan", "250", "CZ…5399")
        store.attachPayment(token, pid.toString())
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.status(502).build()
        val resp = statusResourceFor(upstream, caller, store).paymentSessionStatus(token)
        assertThat(resp.entity.toString()).contains(""""status":"PROCESSING"""")
    }

    // ── standing orders (recurring payments) ──

    private fun soResourceFor(upstream: UpstreamClient, callerParty: UUID): CustomerEdgeResource =
        resourceFor(upstream, callerParty).apply {
            standingOrderServiceUrl = "http://so"
        }

    @Test
    fun `createStandingOrder rejects a debit account owned by another party (IDOR guard)`() {
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, other)
        val body = """{"debitAccountId":"$acct","creditorIban":"CZ123","creditorName":"Landlord",""" +
            """"amountMinorUnits":1500000,"currency":"CZK","frequency":"MONTHLY",""" +
            """"paymentType":"DOMESTIC","startDate":"2026-07-01"}"""
        val resp = soResourceFor(upstream, caller).createStandingOrder(body, null)
        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/standing-orders") }, any(), any()) }
    }

    @Test
    fun `createStandingOrder injects partyId and an idempotency key for an owned account`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, caller)
        var forwarded: String? = null
        every { upstream.post(match { it.contains("/api/v1/standing-orders") }, any(), any()) } answers {
            forwarded = thirdArg()
            Response.status(201).entity("""{"id":"${UUID.randomUUID()}","status":"ACTIVE"}""").build()
        }
        val body = """{"debitAccountId":"$acct","creditorIban":"CZ123","creditorName":"Landlord",""" +
            """"amountMinorUnits":1500000,"currency":"CZK","frequency":"MONTHLY",""" +
            """"paymentType":"DOMESTIC","startDate":"2026-07-01"}"""
        val resp = soResourceFor(upstream, caller).createStandingOrder(body, "idem-1")
        assertThat(resp.status).isEqualTo(201)
        val node = mapper.readTree(forwarded!!)
        assertThat(node.get("partyId").asText()).isEqualTo(caller.toString())
        assertThat(node.get("idempotencyKey").asText()).isEqualTo("idem-1")
    }

    @Test
    fun `pauseStandingOrder refuses an order owned by another party`() {
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val soId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/standing-orders/$soId") }, any()) } returns
            Response.ok("""{"id":"$soId","partyId":"$other","status":"ACTIVE"}""").build()
        val resp = soResourceFor(upstream, caller).pauseStandingOrder(soId)
        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/pause") }, any(), any()) }
    }

    @Test
    fun `cancelStandingOrder forwards a DELETE for the caller's own order`() {
        val caller = UUID.randomUUID()
        val soId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/standing-orders/$soId") }, any()) } returns
            Response.ok("""{"id":"$soId","partyId":"$caller","status":"ACTIVE"}""").build()
        every { upstream.delete(match { it.contains("/standing-orders/$soId") }, any()) } returns
            Response.noContent().build()
        val resp = soResourceFor(upstream, caller).cancelStandingOrder(soId)
        assertThat(resp.status).isEqualTo(204)
        verify(exactly = 1) { upstream.delete(match { it.contains("/standing-orders/$soId") }, any()) }
    }

    // ── self-service onboarding (POST /onboarding/register, ADR-0069 Phase 2) ──

    // ── self-service onboarding (POST /onboarding/register, ADR-0069 Phase 2) ──

    @Test
    fun `registerParty creates the party with id equal to the JWT sub (B1 invariant)`() {
        val sub = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        var forwarded: String? = null
        every { upstream.post(match { it.contains("/api/v1/parties") }, any(), any(), any()) } answers {
            forwarded = thirdArg()
            Response.status(201)
                .entity("""{"id":"$sub","status":"PENDING_KYC","legalName":"Jan Novák"}""").build()
        }
        val resource = CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            jwt = mockk {
                every { getClaim<String>("party_id") } returns null
                every { subject } returns sub.toString()
            }
            objectMapper = ObjectMapper()
            partyServiceUrl = "http://party"
        }

        val resp = resource.registerParty("""{"legalName":"Jan Novák","email":"jan@example.cz"}""")

        assertThat(resp.status).isEqualTo(201)
        val node = mapper.readTree(forwarded!!)
        assertThat(node.get("id").asText()).isEqualTo(sub.toString())
        assertThat(node.get("partyType").asText()).isEqualTo("INDIVIDUAL")
        assertThat(node.get("legalName").asText()).isEqualTo("Jan Novák")
        assertThat(node.get("email").asText()).isEqualTo("jan@example.cz")
        assertThat(resp.entity.toString()).contains(""""partyId":"$sub"""")
    }

    @Test
    fun `registerParty forwards consentGdpr and consentMarketing to party-service`() {
        val sub = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        var forwarded: String? = null
        every { upstream.post(match { it.contains("/api/v1/parties") }, any(), any(), any()) } answers {
            forwarded = thirdArg()
            Response.status(201)
                .entity("""{"id":"$sub","status":"PENDING_KYC"}""").build()
        }
        val resource = CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            jwt = mockk {
                every { getClaim<String>("party_id") } returns null
                every { subject } returns sub.toString()
            }
            objectMapper = ObjectMapper()
            partyServiceUrl = "http://party"
        }

        val resp = resource.registerParty(
            """{"legalName":"Jan Novák","email":"jan@example.cz","consentGdpr":true,"consentMarketing":false}""",
        )

        assertThat(resp.status).isEqualTo(201)
        val node = mapper.readTree(forwarded!!)
        assertThat(node.get("consentGdpr").asBoolean()).isTrue()
        assertThat(node.get("consentMarketing").asBoolean()).isFalse()
    }

    @Test
    fun `registerParty omits consent fields entirely when the body doesn't send them`() {
        val sub = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        var forwarded: String? = null
        every { upstream.post(match { it.contains("/api/v1/parties") }, any(), any(), any()) } answers {
            forwarded = thirdArg()
            Response.status(201)
                .entity("""{"id":"$sub","status":"PENDING_KYC"}""").build()
        }
        val resource = CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            jwt = mockk {
                every { getClaim<String>("party_id") } returns null
                every { subject } returns sub.toString()
            }
            objectMapper = ObjectMapper()
            partyServiceUrl = "http://party"
        }

        resource.registerParty("""{"legalName":"Jan Novák","email":"jan@example.cz"}""")

        val node = mapper.readTree(forwarded!!)
        assertThat(node.has("consentGdpr")).isFalse()
        assertThat(node.has("consentMarketing")).isFalse()
    }

    @Test
    fun `registerParty falls back to the token name and email claims when the body omits them`() {
        val sub = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        var forwarded: String? = null
        every { upstream.post(any(), any(), any(), any()) } answers {
            forwarded = thirdArg()
            Response.status(201).entity("""{"id":"$sub","status":"PENDING_KYC"}""").build()
        }
        val resource = CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            jwt = mockk {
                every { getClaim<String>("party_id") } returns null
                every { subject } returns sub.toString()
                every { getClaim<String>("name") } returns "Eva Malá"
                every { getClaim<String>("email") } returns "eva@example.cz"
            }
            objectMapper = ObjectMapper()
            partyServiceUrl = "http://party"
        }

        val resp = resource.registerParty("{}")

        assertThat(resp.status).isEqualTo(201)
        val node = mapper.readTree(forwarded!!)
        assertThat(node.get("legalName").asText()).isEqualTo("Eva Malá")
        assertThat(node.get("email").asText()).isEqualTo("eva@example.cz")
    }

    // ── FX rate-sheet projection (kurzovní lístek) ──

    @Test
    fun `mapFxRate projects a single pair to mid-price with timestamp`() {
        val upstream = """{"baseCurrency":"EUR","quoteCurrency":"CZK","bidRate":"24.80","askRate":"25.20",
            "rateType":"SPOT","source":"ECB","validFrom":"2026-06-14T00:00:00Z"}"""
        val out = mapper.readTree(CustomerEdgeResource.mapFxRate(mapper, upstream, "EUR", "CZK")!!)
        assertThat(out.get("base").asText()).isEqualTo("EUR")
        assertThat(out.get("quote").asText()).isEqualTo("CZK")
        assertThat(out.get("rate").asText()).isEqualTo("25") // (24.80+25.20)/2 = 25.00 → stripped
        assertThat(out.get("timestamp").asText()).isEqualTo("2026-06-14T00:00:00Z")
    }

    @Test
    fun `mapFxRateList projects the rate sheet keeping bid ask and mid`() {
        val upstream = """[
            {"baseCurrency":"EUR","quoteCurrency":"CZK","bidRate":"24.80","askRate":"25.20",
             "validFrom":"2026-06-14T00:00:00Z"},
            {"baseCurrency":"USD","quoteCurrency":"CZK","bidRate":"22.90","askRate":"23.10",
             "createdAt":"2026-06-14T12:00:00Z"}
        ]"""
        val out = mapper.readTree(CustomerEdgeResource.mapFxRateList(mapper, upstream)!!)
        assertThat(out.isArray).isTrue()
        assertThat(out.size()).isEqualTo(2)
        val eur = out.get(0)
        assertThat(eur.get("base").asText()).isEqualTo("EUR")
        assertThat(eur.get("quote").asText()).isEqualTo("CZK")
        assertThat(eur.get("rate").asText()).isEqualTo("25")
        assertThat(eur.get("bid").asText()).isEqualTo("24.8")
        assertThat(eur.get("ask").asText()).isEqualTo("25.2")
        assertThat(eur.get("timestamp").asText()).isEqualTo("2026-06-14T00:00:00Z")
        assertThat(out.get(1).get("timestamp").asText()).isEqualTo("2026-06-14T12:00:00Z")
    }

    @Test
    fun `mapFxRateList skips rows missing currency or rate but keeps the good ones`() {
        val upstream = """[
            {"baseCurrency":"EUR","quoteCurrency":"CZK","bidRate":"24.80","askRate":"25.20"},
            {"baseCurrency":"GBP"},
            {"quoteCurrency":"CZK","bidRate":"1.0","askRate":"1.0"}
        ]"""
        val out = mapper.readTree(CustomerEdgeResource.mapFxRateList(mapper, upstream)!!)
        assertThat(out.size()).isEqualTo(1)
        assertThat(out.get(0).get("base").asText()).isEqualTo("EUR")
    }

    @Test
    fun `mapFxRateList returns null on a non-array body`() {
        assertThat(CustomerEdgeResource.mapFxRateList(mapper, """{"not":"an array"}""")).isNull()
    }

    @Test
    fun `mapFxRateList enriches bank rows with CNB reference mid and spreadPct`() {
        // CNB fixing row (source=CNB) must NOT appear in output; its midRate becomes the reference.
        // Bank row (source=INTERNAL) must gain refMid and spreadPct = (ask-refMid)/refMid*100.
        val upstream = """[
            {"baseCurrency":"EUR","quoteCurrency":"CZK","source":"CNB",
             "bidRate":"24.90","askRate":"25.10","midRate":"25.00",
             "validFrom":"2026-06-14T00:00:00Z"},
            {"baseCurrency":"EUR","quoteCurrency":"CZK","source":"INTERNAL",
             "bidRate":"24.75","askRate":"25.25","midRate":"25.00",
             "validFrom":"2026-06-14T00:00:00Z"}
        ]"""
        val out = mapper.readTree(CustomerEdgeResource.mapFxRateList(mapper, upstream)!!)
        assertThat(out.isArray).isTrue()
        // CNB row must be excluded from the output
        assertThat(out.size()).isEqualTo(1)
        val row = out.get(0)
        assertThat(row.get("base").asText()).isEqualTo("EUR")
        // refMid comes from the CNB midRate field
        assertThat(row.get("refMid").asText()).isEqualTo("25")
        // spreadPct = (ask 25.25 - refMid 25.00) / 25.00 * 100 = 1.00
        assertThat(row.get("spreadPct").asText()).isEqualTo("1")
    }

    @Test
    fun `mapFxRateList omits refMid and spreadPct when no CNB rate is present for the pair`() {
        val upstream = """[
            {"baseCurrency":"USD","quoteCurrency":"CZK","source":"INTERNAL",
             "bidRate":"22.90","askRate":"23.10","validFrom":"2026-06-14T00:00:00Z"}
        ]"""
        val out = mapper.readTree(CustomerEdgeResource.mapFxRateList(mapper, upstream)!!)
        assertThat(out.size()).isEqualTo(1)
        assertThat(out.get(0).has("refMid")).isFalse()
        assertThat(out.get(0).has("spreadPct")).isFalse()
    }

    @Test
    fun `mapFxHistoryList keeps CNB rows sorts oldest input newest first and deduplicates timestamp`() {
        val upstream = """[
            {"baseCurrency":"EUR","quoteCurrency":"CZK","source":"CNB","bidRate":"24.8","askRate":"25.2","validFrom":"2026-05-01T00:00:00Z"},
            {"baseCurrency":"EUR","quoteCurrency":"CZK","source":"CNB","bidRate":"24.9","askRate":"25.1","validFrom":"2026-06-01T00:00:00Z"},
            {"baseCurrency":"EUR","quoteCurrency":"CZK","source":"CNB","bidRate":"24.9","askRate":"25.1","validFrom":"2026-06-01T00:00:00Z"}
        ]"""

        val out = mapper.readTree(CustomerEdgeResource.mapFxHistoryList(mapper, upstream)!!)

        assertThat(out).hasSize(2)
        assertThat(out[0]["timestamp"].asText()).isEqualTo("2026-06-01T00:00:00Z")
        assertThat(out[1]["timestamp"].asText()).isEqualTo("2026-05-01T00:00:00Z")
    }

    @Test
    fun `three month trend uses calendar months rather than a row or day count`() {
        val now = java.time.Instant.parse("2026-05-31T12:00:00Z")
        assertThat(CustomerEdgeResource.threeMonthWindowStart(now))
            .isEqualTo(java.time.Instant.parse("2026-02-28T12:00:00Z"))
    }

    // --- isValidInstant ---

    @Test
    fun `isValidInstant accepts a well-formed ISO-8601 instant`() {
        assertThat(CustomerEdgeResource.isValidInstant("2026-01-15T00:00:00Z")).isTrue()
        assertThat(CustomerEdgeResource.isValidInstant("2025-06-01T12:30:00.000Z")).isTrue()
    }

    @Test
    fun `isValidInstant rejects malformed strings`() {
        assertThat(CustomerEdgeResource.isValidInstant("not-a-date")).isFalse()
        assertThat(CustomerEdgeResource.isValidInstant("2026-13-01T00:00:00Z")).isFalse()
        assertThat(CustomerEdgeResource.isValidInstant("2026-01-01")).isFalse() // date only, not instant
        assertThat(CustomerEdgeResource.isValidInstant("")).isFalse()
    }

    // --- fxRateHistory (unit — upstream wiring + param validation) ---

    private fun fxResource(upstream: UpstreamClient, callerParty: UUID): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns callerParty.toString()
            every { subject } returns callerParty.toString()
        }
        objectMapper = ObjectMapper()
        fxServiceUrl = "http://fx"
    }

    @Test
    fun `fxRateHistory returns 400 for invalid currency code`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>(relaxed = true)
        val resp = fxResource(upstream, caller).fxRateHistory("eur", "CZK", null, null, null, null)
        assertThat(resp.status).isEqualTo(400)
    }

    @Test
    fun `fxRateHistory returns 400 for malformed from instant`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>(relaxed = true)
        val resp = fxResource(upstream, caller).fxRateHistory("EUR", "CZK", "not-a-date", null, null, null)
        assertThat(resp.status).isEqualTo(400)
    }

    @Test
    fun `fxRateHistory returns 400 for malformed to instant`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>(relaxed = true)
        val resp = fxResource(upstream, caller).fxRateHistory("EUR", "CZK", null, "bad", null, null)
        assertThat(resp.status).isEqualTo(400)
    }

    @Test
    fun `fxRateHistory builds correct upstream URL without source filter`() {
        val caller = UUID.randomUUID()
        val urlSlot = slot<String>()
        val upstream = mockk<UpstreamClient> {
            every { get(capture(urlSlot), any()) } returns Response.ok("""[]""").build()
        }
        fxResource(upstream, caller).fxRateHistory("EUR", "CZK", null, null, 30, null)
        assertThat(urlSlot.captured).doesNotContain("source=INTERNAL")
        assertThat(urlSlot.captured).contains("/api/v1/fx/rates/EUR/CZK/history")
        assertThat(urlSlot.captured).contains("limit=30")
    }

    @Test
    fun `fxRateHistory caps limit at 365`() {
        val caller = UUID.randomUUID()
        val urlSlot = slot<String>()
        val upstream = mockk<UpstreamClient> {
            every { get(capture(urlSlot), any()) } returns Response.ok("""[]""").build()
        }
        fxResource(upstream, caller).fxRateHistory("EUR", "CZK", null, null, 9999, null)
        assertThat(urlSlot.captured).contains("limit=365")
    }

    @Test
    fun `fxRateHistory forwards from and to as encoded params`() {
        val caller = UUID.randomUUID()
        val urlSlot = slot<String>()
        val upstream = mockk<UpstreamClient> {
            every { get(capture(urlSlot), any()) } returns Response.ok("""[]""").build()
        }
        fxResource(
            upstream,
            caller,
        ).fxRateHistory("EUR", "CZK", "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", null, null)
        assertThat(urlSlot.captured).contains("from=")
        assertThat(urlSlot.captured).contains("to=")
    }

    // ── customer product catalogue (/products) ────────────────────────────────
    // Every case here is a product the OPERATOR catalogue legitimately holds and the
    // CUSTOMER must never see. The endpoint's whole job is that gap, so its tests are
    // the negative ones.

    private fun catalogueProduct(
        id: UUID,
        type: String = "SAVINGS",
        status: String = "ACTIVE",
        public: Boolean = true,
        extra: String = "",
    ): String = """{
        "id":"$id", "code":"${type}_STANDARD", "name":"Product $type", "type":"$type",
        "currency":"CZK", "status":"$status", "isPublic":$public, "fee":0.0,
        "versionHistory":[{"version":"0.9.0"}], "eligibilitySegments":["ALL"]$extra
    }"""

    private fun offersFrom(upstream: UpstreamClient, party: UUID): com.fasterxml.jackson.databind.JsonNode {
        val response = resourceFor(upstream, party).listProductOffers(null)
        assertThat(response.status).isEqualTo(200)
        return ObjectMapper().readTree(ObjectMapper().writeValueAsString(response.entity)).path("items")
    }

    @Test
    fun `a draft product is not discoverable`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns
            Response.ok("[${catalogueProduct(UUID.randomUUID(), status = "DRAFT")}]").build()
        assertThat(offersFrom(upstream, party)).isEmpty()
    }

    @Test
    fun `a private product is not discoverable`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns
            Response.ok("[${catalogueProduct(UUID.randomUUID(), public = false)}]").build()
        assertThat(offersFrom(upstream, party)).isEmpty()
    }

    @Test
    fun `a withdrawn product is not discoverable`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            "[${catalogueProduct(UUID.randomUUID(), extra = ""","validTo":"2020-01-01"""")}]",
        ).build()
        assertThat(offersFrom(upstream, party)).isEmpty()
    }

    @Test
    fun `a future-dated product is not discoverable`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            "[${catalogueProduct(UUID.randomUUID(), extra = ""","validFrom":"2999-01-01"""")}]",
        ).build()
        assertThat(offersFrom(upstream, party)).isEmpty()
    }

    @Test
    fun `a regulated type outside the allow-list is not discoverable`() {
        // MORTGAGE and CREDIT_CARD exist in the catalogue and each needs its own suitability
        // journey. Surfacing one here would let the app offer it with no path to take it.
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            "[${catalogueProduct(UUID.randomUUID(), type = "MORTGAGE")}," +
                "${catalogueProduct(UUID.randomUUID(), type = "CREDIT_CARD")}]",
        ).build()
        assertThat(offersFrom(upstream, party)).isEmpty()
    }

    @Test
    fun `internal fields never cross the boundary`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns
            Response.ok("[${catalogueProduct(UUID.randomUUID())}]").build()
        val offer = offersFrom(upstream, party).first()
        assertThat(offer.has("versionHistory")).isFalse()
        assertThat(offer.has("eligibilitySegments")).isFalse()
        assertThat(offer.has("status")).isFalse()
        assertThat(offer.has("isPublic")).isFalse()
    }

    @Test
    fun `a term deposit reports its own fixed rate and term`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            "[${catalogueProduct(
                UUID.randomUUID(),
                type = "TERM_DEPOSIT",
                extra = ""","termDepositConfig":{"termMonths":12,"interestRateAnnual":4.8}""",
            )}]",
        ).build()
        val offer = offersFrom(upstream, party).first()
        assertThat(offer.path("annualRate").asDouble()).isEqualTo(4.8)
        assertThat(offer.path("term").path("termMonths").asInt()).isEqualTo(12)
    }

    @Test
    fun `savings keeps its tiers rather than being flattened to one rate`() {
        // Savings prices by balance tier. Collapsing that into a single "from" number would be
        // the app quoting a rate the bank never set for the customer's actual balance.
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            "[${catalogueProduct(
                UUID.randomUUID(),
                extra = ""","savingsConfig":{"interestTiers":[{"upTo":100000,"rateAnnual":3.5}]}""",
            )}]",
        ).build()
        val offer = offersFrom(upstream, party).first()
        assertThat(offer.path("savings").path("interestTiers")).hasSize(1)
        assertThat(offer.has("annualRate")).isFalse()
    }

    @Test
    fun `a current account carries no rate at all`() {
        // Absent, not zero: 0 % is a price, "not priced" is not.
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns
            Response.ok("[${catalogueProduct(UUID.randomUUID(), type = "CURRENT")}]").build()
        val offer = offersFrom(upstream, party).first()
        assertThat(offer.has("annualRate")).isFalse()
        assertThat(offer.has("savings")).isFalse()
        // The monthly fee IS copied even at zero — "no fee, forever" is the product's pitch.
        assertThat(offer.path("fee").asDouble()).isEqualTo(0.0)
    }

    @Test
    fun `an unknown type is refused rather than silently listing everything`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val response = resourceFor(upstream, party).listProductOffers("MORTGAGE")
        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `a catalogue outage is a 503, never an empty catalogue`() {
        // An empty list reads as "we offer nothing", which is a claim about the bank.
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.status(500).build()
        assertThat(resourceFor(upstream, party).listProductOffers(null).status).isEqualTo(503)
    }
}
