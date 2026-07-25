// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider-side Pact verification for customer-edge (issue #2322). Replays copilot's
 * `CustomerEdgePactConsumerTest` — the seven `/customer/v1` reads every copilot READ tool is
 * a thin wrapper over — against a real booted instance.
 *
 * Git-pact (`@PactFolder`, ADR-0063): reads consumer pacts from the monorepo-root `pacts/` dir and
 * **always runs** — no broker, no CI secret, no gate. That matters here specifically: copilot
 * propagates the customer's OWN bearer on these calls (on-behalf-of, ADR-0065 / ADR-0089 D5), so a
 * wrong response shape or a route the edge stopped serving degrades a figure the assistant
 * narrates to the customer, silently. A consumer pact nobody replays cannot catch that — see
 * CLAUDE.md "Contract tests (Pact)".
 *
 * **This must remain the ONLY `@Provider("openbank-customer-edge")` class in the repo** — two
 * classes with the same provider name both pull every pact and collide.
 *
 * Auth: `@TestSecurity` + `@OidcSecurity(claims = [Claim("party_id", ...)])` synthesizes the
 * customer JWT `CustomerEdgeResource.customer()` reads (`org.eclipse.microprofile.jwt.JsonWebToken`
 * under `quarkus-oidc`) — the same mechanism `openbank-mcp-service`'s `McpAuditEventIT` already
 * uses for a `quarkus-oidc`-backed resource server. `@Authorize` is satisfied fleet-wide in tests
 * by `MockAuthzProducer` (`@Mock`, auto-applied — no PDP needed). The party id is fixed for the
 * whole class: `@OidcSecurity`'s `claims` must be compile-time constants, so every interaction's
 * fixture data is built around this one party/account pair.
 *
 * Upstreams are stubbed by [StubUpstreamResource] — a single loopback HTTP server standing in for
 * all seven upstream services plus the Keycloak token endpoint, since customer-edge is a BFF that
 * calls out through one generic `UpstreamClient`, not seven typed REST clients. Ownership-gated
 * reads (balances, transactions, statements) additionally need the account-ownership lookup
 * (`GET /api/v1/accounts/{accountId}`) stubbed — [ownedAccountFixture] is that shared fixture.
 */
@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class)
@TestSecurity(user = "customer:$PARTY_ID", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = PARTY_ID)])
@Provider("openbank-customer-edge")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class CustomerEdgeContractProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        StubUpstreamResource.reset()
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // context is null on the @IgnoreNoPactsToVerify dummy invocation — skip gracefully.
        context?.verifyInteraction()
    }

    /** account-service's ownership-check response every ownership-gated read needs first. */
    private fun ownedAccountFixture(): String = """
        {"id":"$ACCOUNT_ID","accountNumber":"123456789/0800","accountType":"CURRENT",
         "partyId":"$PARTY_ID","productId":"current-standard","currencyCode":"CZK"}
    """.trimIndent()

    @State("the customer has one account")
    fun customerHasOneAccount() {
        StubUpstreamResource.stub(
            "/api/v1/accounts",
            body = """{"data":[$FULL_ACCOUNT_JSON]}""",
        )
    }

    @State("the customer owns an account with a balance")
    fun customerOwnsAnAccountWithABalance() {
        StubUpstreamResource.stub("/api/v1/accounts/$ACCOUNT_ID", body = ownedAccountFixture())
        StubUpstreamResource.stub(
            "/api/v1/balances/$ACCOUNT_ID",
            // Bare JSON array — balance-service's real shape (issue #2322 / #2458). NOT wrapped.
            body = """[{"currency":"CZK","bookedAmount":1000.00,"availableAmount":950.00}]""",
        )
    }

    @State("the customer owns an account with transactions")
    fun customerOwnsAnAccountWithTransactions() {
        StubUpstreamResource.stub("/api/v1/accounts/$ACCOUNT_ID", body = ownedAccountFixture())
        StubUpstreamResource.stub(
            "/api/v1/transactions",
            body = """
                {"data":[{"amount":250.00,"currencyCode":"CZK","type":"PAYMENT","status":"BOOKED",
                          "bookingDate":"2026-07-01","description":"Rent"}],
                 "pagination":{"limit":10,"hasNextPage":false}}
            """.trimIndent(),
        )
    }

    @State("fx rates are available")
    fun fxRatesAreAvailable() {
        StubUpstreamResource.stub(
            "/api/v1/fx/rates",
            // Raw fx-service shape (mapFxRateRow reads baseCurrency/quoteCurrency/bidRate/askRate);
            // source != "CNB" so it lands in the bank-rate list the edge projects, not the CNB
            // reference map.
            body = """
                [{"baseCurrency":"EUR","quoteCurrency":"CZK","bidRate":"24.90","askRate":"25.10",
                  "source":"INTERNAL","validFrom":"2026-07-25T08:00:00Z"}]
            """.trimIndent(),
        )
    }

    @State("the customer has a card")
    fun customerHasACard() {
        StubUpstreamResource.stub(
            "/api/v1/cards/party/$PARTY_ID",
            body = """
                [{"id":"$CARD_ID","maskedPan":"**** **** **** 1234","cardType":"DEBIT",
                  "network":"VISA","status":"ACTIVE","expiryDate":"2029-12-31","currency":"CZK"}]
            """.trimIndent(),
        )
    }

    @State("the customer owns an account with statements")
    fun customerOwnsAnAccountWithStatements() {
        StubUpstreamResource.stub("/api/v1/accounts/$ACCOUNT_ID", body = ownedAccountFixture())
        StubUpstreamResource.stub(
            "/api/v1/statements/$ACCOUNT_ID",
            body = """
                [{"id":"$STATEMENT_ID","pocketCurrency":"CZK","periodFrom":"2026-06-01",
                  "periodTo":"2026-06-30","legalSequenceNumber":7,"openingBalance":900.00,
                  "closingBalance":1000.00,"entryCount":12,"status":"ISSUED"}]
            """.trimIndent(),
        )
    }

    @State("the customer has a standing order")
    fun customerHasAStandingOrder() {
        StubUpstreamResource.stub(
            "/api/v1/standing-orders/party/$PARTY_ID",
            body = """
                [{"id":"$STANDING_ORDER_ID","creditorIban":"CZ6508000000192000145399",
                  "creditorName":"Elektrárna a.s.","status":"ACTIVE","frequency":"MONTHLY",
                  "amount":1500.0,"currency":"CZK","nextExecutionDate":"2026-08-01",
                  "remittanceInfo":"Elektřina"}]
            """.trimIndent(),
        )
    }

    companion object {
        private const val FULL_ACCOUNT_JSON = """
            {"id":"$ACCOUNT_ID","accountNumber":"123456789/0800","accountType":"CURRENT",
             "partyId":"$PARTY_ID","productId":"current-standard","currencyCode":"CZK",
             "status":"ACTIVE"}
        """
    }
}

// Compile-time constants for @OidcSecurity(claims = [...]) — must live outside the class body.
internal const val PARTY_ID = "22222222-2222-2222-2222-222222222222"
internal const val ACCOUNT_ID = "33333333-3333-3333-3333-333333333333"
internal const val CARD_ID = "44444444-4444-4444-4444-444444444444"
internal const val STATEMENT_ID = "55555555-5555-5555-5555-555555555555"
internal const val STANDING_ORDER_ID = "66666666-6666-6666-6666-666666666666"
