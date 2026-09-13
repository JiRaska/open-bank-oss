// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.NetWorthComposer
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The composition has exactly one property worth defending, and every test here is about it:
 * **a branch whose owner did not answer must never contribute zero.**
 *
 * A fan-out that fails soft produces a smaller number that still renders, and the customer reads
 * it as fact. So each test that exercises a failure asserts BOTH that the branch says UNAVAILABLE
 * and that the totals refuse to call themselves complete — either alone would pass against a
 * composer that had quietly dropped the branch.
 */
class NetWorthComposerTest {

    private val party = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val entityId = UUID.randomUUID()
    private val accountBase = "http://account-service.core.svc:8100"
    private val balanceBase = "http://balance-service.balances.svc:8104"
    private val lendingBase = "http://lending-service.lending.svc:8126"
    private val wealthBase = "http://wealth-service.wealth.svc:8154"
    private val partyBase = "http://party-service.party.svc:8111"

    private val mapper = ObjectMapper()

    private fun composer(upstream: UpstreamClient) = NetWorthComposer().apply {
        this.upstream = upstream
        this.objectMapper = mapper
        this.clock = Clock.fixed(Instant.parse("2026-09-13T08:00:00Z"), ZoneOffset.UTC)
        this.accountServiceUrl = accountBase
        this.balanceServiceUrl = balanceBase
        this.lendingServiceUrl = lendingBase
        this.wealthServiceUrl = wealthBase
        this.partyServiceUrl = partyBase
    }

    private fun ok(json: String): Response = Response.ok(json).build()
    private fun down(): Response = Response.status(503).build()

    /** Every upstream healthy, unless a test overrides one. */
    private fun healthyUpstream(): UpstreamClient = mockk<UpstreamClient>().also { u ->
        every { u.get("$accountBase/api/v1/accounts", party.toString()) } returns
            ok("""[{"id":"$accountId","iban":"CZ6508000000192000145399"}]""")
        every { u.get("$balanceBase/api/v1/balances/$accountId", party.toString()) } returns
            ok("""{"balances":[{"currency":"CZK","bookedBalance":150000.00}]}""")
        every { u.get("$lendingBase/api/v1/lending/loans?partyId=$party", party.toString()) } returns
            ok("""[{"id":"l1","productCode":"MORTGAGE","outstandingPrincipal":2000000.00,"currency":"CZK"}]""")
        every { u.get("$wealthBase/api/v1/holdings", party.toString()) } returns
            ok(
                """[{"holdingType":"REAL_ESTATE","label":"Flat","attributableAmount":5000000.00,
                   "currency":"CZK","isLiability":false,"valuationSource":"CUSTOMER_DECLARED",
                   "valuationAgeDays":1200}]
                """.trimIndent().replace("\n", ""),
            )
        every { u.get("$partyBase/api/v1/parties/$party/acting-for", party.toString()) } returns
            ok("""[{"partyId":"$entityId","legalName":"Příklad s.r.o."}]""")
    }

    private fun branch(root: com.fasterxml.jackson.databind.JsonNode, name: String) =
        root.path("branches").first { it.path("branch").asText() == name }

    private fun czk(root: com.fasterxml.jackson.databind.JsonNode) =
        root.path("totals").path("byCurrency").first { it.path("currency").asText() == "CZK" }
            .path("amount").decimalValue()

    @Test
    fun `the happy path nets liabilities against assets, per currency`() {
        val root = composer(healthyUpstream()).compose(party)

        // 150 000 cash + 5 000 000 declared - 2 000 000 mortgage
        assertThat(czk(root)).isEqualByComparingTo("3150000.00")
        assertThat(root.path("totals").path("complete").asBoolean()).isTrue()
        assertThat(root.path("totals").path("converted").asBoolean()).isFalse()
    }

    @Test
    fun `a branch whose owner is down is UNAVAILABLE and the total refuses to call itself complete`() {
        val upstream = healthyUpstream()
        every { upstream.get("$lendingBase/api/v1/lending/loans?partyId=$party", party.toString()) } returns down()

        val root = composer(upstream).compose(party)
        val loans = branch(root, "LOANS")

        // Both halves matter. Without the first, a composer that dropped the branch silently would
        // pass; without the second, one that reported UNAVAILABLE and still summed zero would.
        assertThat(loans.path("status").asText()).isEqualTo("UNAVAILABLE")
        assertThat(loans.path("leaves")).isEmpty()
        assertThat(root.path("totals").path("complete").asBoolean()).isFalse()
        assertThat(root.path("totals").path("missingBranches").map { it.asText() }).contains("LOANS")

        // And the decisive one: the mortgage must NOT have been netted off as zero. Had the branch
        // contributed zero, this would read 5 150 000 and look like a perfectly good answer.
        assertThat(czk(root)).isEqualByComparingTo("5150000.00")
        assertThat(root.path("totals").path("contributingBranches").map { it.asText() })
            .doesNotContain("LOANS")
    }

    @Test
    fun `an upstream that throws is UNAVAILABLE, not a 500 for the whole composition`() {
        val upstream = healthyUpstream()
        every { upstream.get("$wealthBase/api/v1/holdings", party.toString()) } throws
            RuntimeException("connection reset")

        val root = composer(upstream).compose(party)

        assertThat(branch(root, "DECLARED_HOLDINGS").path("status").asText()).isEqualTo("UNAVAILABLE")
        // The customer can still see their cash while one upstream is down.
        assertThat(branch(root, "CASH").path("status").asText()).isEqualTo("AVAILABLE")
        assertThat(root.path("totals").path("complete").asBoolean()).isFalse()
    }

    @Test
    fun `unparseable JSON is UNAVAILABLE, not an empty AVAILABLE branch`() {
        val upstream = healthyUpstream()
        every { upstream.get("$lendingBase/api/v1/lending/loans?partyId=$party", party.toString()) } returns
            ok("not json at all")

        assertThat(branch(composer(upstream).compose(party), "LOANS").path("status").asText())
            .isEqualTo("UNAVAILABLE")
    }

    @Test
    fun `an owner that answers with nothing is AVAILABLE and empty, which is a different fact`() {
        val upstream = healthyUpstream()
        every { upstream.get("$lendingBase/api/v1/lending/loans?partyId=$party", party.toString()) } returns ok("[]")

        val root = composer(upstream).compose(party)
        val loans = branch(root, "LOANS")

        assertThat(loans.path("status").asText()).isEqualTo("AVAILABLE")
        assertThat(loans.path("leaves")).isEmpty()
        // "no loans" is a complete answer; "could not ask" is not.
        assertThat(root.path("totals").path("complete").asBoolean()).isTrue()
    }

    @Test
    fun `a declared holding keeps its source and its age on the wire`() {
        val root = composer(healthyUpstream()).compose(party)
        val leaf = branch(root, "DECLARED_HOLDINGS").path("leaves").first()

        // Without these two the app cannot tell a bank figure from something the customer typed
        // years ago, and ADR-0301 D2 exists to prevent exactly that.
        assertThat(leaf.path("valuationSource").asText()).isEqualTo("CUSTOMER_DECLARED")
        assertThat(leaf.path("valuationAgeDays").asLong()).isEqualTo(1200L)
        assertThat(leaf.path("sourceService").asText()).isEqualTo("wealth-service")
    }

    @Test
    fun `entity stakes carry no amount and move no total`() {
        val root = composer(healthyUpstream()).compose(party)
        val stakes = branch(root, "ENTITY_STAKES")
        val leaf = stakes.path("leaves").first()

        assertThat(stakes.path("status").asText()).isEqualTo("AVAILABLE")
        assertThat(leaf.path("valued").asBoolean()).isFalse()
        assertThat(leaf.has("amount")).isFalse()
        // The total is unchanged by their presence — a zero here would be the same lie as a
        // missing branch contributing zero.
        assertThat(czk(root)).isEqualByComparingTo("3150000.00")
    }

    @Test
    fun `currencies are never mixed`() {
        val upstream = healthyUpstream()
        every { upstream.get("$balanceBase/api/v1/balances/$accountId", party.toString()) } returns
            ok("""{"balances":[{"currency":"CZK","bookedBalance":100.00},{"currency":"EUR","bookedBalance":50.00}]}""")

        val totals = composer(upstream).compose(party).path("totals").path("byCurrency")
        val eur = totals.first { it.path("currency").asText() == "EUR" }.path("amount").decimalValue()

        assertThat(eur).isEqualByComparingTo("50.00")
        assertThat(totals).hasSize(2)
    }

    @Test
    fun `a balance read that fails does not silently zero that account`() {
        val upstream = healthyUpstream()
        every { upstream.get("$balanceBase/api/v1/balances/$accountId", party.toString()) } returns down()

        val root = composer(upstream).compose(party)

        assertThat(branch(root, "CASH").path("status").asText()).isEqualTo("UNAVAILABLE")
        assertThat(root.path("totals").path("complete").asBoolean()).isFalse()
    }

    @Test
    fun `every leaf names the service that answered for it`() {
        val root = composer(healthyUpstream()).compose(party)
        val sources = root.path("branches").flatMap { b -> b.path("leaves").map { it.path("sourceService").asText() } }

        assertThat(sources).isNotEmpty
        assertThat(sources).allSatisfy { assertThat(it).isNotBlank() }
    }
}
