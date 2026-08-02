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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-side provider verification for openbank-customer-edge, the published-result counterpart to
 * [CustomerEdgeContractProviderVerificationTest].
 *
 * WHY BOTH EXIST. A `@PactFolder` test replays the COMMITTED pact from disk: it proves this
 * provider still honours the contract, on every PR, with no infrastructure. It never contacts
 * the broker, so it publishes nothing — and `can-i-deploy` reads published verification
 * results, not green test runs. Without this class the broker never learned that
 * openbank-customer-edge verifies anything, so its consumers (openbank-copilot-service) stayed
 * permanently UNVERIFIED and could not be deployed (issue #3232).
 *
 * A second `@Provider("openbank-customer-edge")` class is safe here for the reason
 * CLAUDE.md gives for ledger-service's identical pair: the collision it warns about is HTTP vs
 * MESSAGE target dispatch fighting over the same `@BeforeEach`, and both classes here use the
 * same target type, so verifying the same interactions from two pact sources is at worst
 * redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane, which have no broker
 * configured. It runs on the main push, where `_service-ci.yml` sets `PUBLISH_RESULTS=true`
 * — that is the run whose result `can-i-deploy` gates the deploy on. The `@PactFolder` class
 * keeps running unconditionally, so PR-time contract coverage is unchanged by this addition.
 */
@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class)
@TestSecurity(user = "customer:$PARTY_ID", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = PARTY_ID)])
@Provider("openbank-customer-edge")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class CustomerEdgeContractBrokerProviderVerificationTest {
    // NOTE: the fixture ids (PARTY_ID, ACCOUNT_ID, ...) are file-level `internal const val`s
    // declared in CustomerEdgeContractProviderVerificationTest.kt, in this same package.
    // They are deliberately NOT redeclared here — a copy would be a second top-level
    // declaration of the same name in the same package, which is an overload-resolution
    // ambiguity on every use (that is exactly how the first draft of this file failed to
    // compile). One definition, two tests.

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
