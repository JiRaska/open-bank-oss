// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.copilot.infrastructure.client.AccountsPage
import com.openbank.copilot.infrastructure.client.BalanceDto
import com.openbank.copilot.infrastructure.client.CardDto
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import com.openbank.copilot.infrastructure.client.FxRateDto
import com.openbank.copilot.infrastructure.client.StandingOrderDto
import com.openbank.copilot.infrastructure.client.StatementDto
import com.openbank.copilot.infrastructure.client.TransactionPage
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the seven `/customer/v1` reads copilot's tools depend on
 * (issue #2322). The generated pact is committed to
 * `pacts/openbank-copilot-service-openbank-customer-edge.json` (git-pact, ADR-0063) and replayed
 * by `CustomerEdgeContractProviderVerificationTest` (`@PactFolder("../pacts")`) — that test always
 * runs, no broker involved.
 *
 * Every read tool (`AccountsTool`, `BalanceTool`, `TransactionTool`, `FxRatesTool`,
 * `CardStatusTool`, `StatementsTool`, `ScheduledPaymentsTool`, `AccountsWithBalancesTool`) mocks
 * `CustomerEdgeRestClient` in its own unit test, so a wrong path, a renamed field or a dropped
 * route on customer-edge's side is invisible there — exactly the finrep #2269 asymmetry.
 *
 * **The expected path is a LITERAL in every interaction below; only the outgoing request is
 * reflected off the client's own `@Path`** (CLAUDE.md "Contract tests", measured on #2290 —
 * deriving both sides is vacuous). [clientDerivedPath] pins the annotations to the literal
 * instead.
 *
 * IMPORTANT — regenerate on change: re-run this test
 * (`./gradlew :openbank-copilot-service:test --tests "*CustomerEdgePactConsumerTest*"`) and
 * commit the updated pact JSON in the same PR.
 */
/**
 * MATCHER POLICY (issue #2425) — why nothing here is pinned by value.
 *
 * The #2425 sweep pins enum-like strings with `stringValue` wherever a `type` matcher made the
 * assertion vacuous. Every interaction in THIS file was reviewed and every string was deliberately
 * left type-matched, because they share one property: they are elements of a heterogeneous LIST,
 * and none of them is echoed from the request.
 *
 * A customer legitimately holds a CURRENT and a SAVINGS account, in CZK and in EUR; a DEBIT and a
 * CREDIT card, on VISA and on Mastercard; transactions of many types and statuses; and the FX
 * endpoint returns every pair the bank quotes. Pinning `accountType` to "CURRENT" would not assert
 * "the type is spelled correctly" — it would assert "the customer's only account is a current
 * account", a claim this contract does not make and the first customer with a savings account
 * falsifies. The seeded provider state happens to contain one element of each; that is a property
 * of the fixture, not of the contract.
 *
 * The line the sweep draws is echo-or-lifecycle vs list-element: pin a value that the request
 * determines (a currency in the path, an `asOf` query parameter, a product code) or that the
 * provider's own lifecycle fixes (a freshly posted journal is POSTED). Leave a list element alone.
 * The sibling pins under #2533 — fx-service's currency pair, ledger-service's `asOf` and journal
 * status — are all the former; nothing here is.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-customer-edge", pactVersion = PactSpecVersion.V3)
class CustomerEdgePactConsumerTest {

    private val mapper = jacksonObjectMapper()

    // --- accounts -----------------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun oneAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the customer has one account")
        .uponReceiving("GET the customer's accounts")
        .path(ACCOUNTS_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("data") { a ->
                    a.`object` { acc ->
                        acc.uuid("id", UUID.fromString(ACCOUNT_ID))
                        acc.stringType("accountNumber", "123456789/0800")
                        acc.stringType("accountType", "CURRENT")
                        acc.stringType("currencyCode", "CZK")
                        acc.stringType("status", "ACTIVE")
                    }
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "oneAccountPact")
    fun `listAccounts parses the edge's real accounts page shape`(mockServer: MockServer) {
        assertClientPathMatches(ACCOUNTS_PATH, clientDerivedPath("listAccounts"))

        val raw = get(mockServer, clientDerivedPath("listAccounts"))

        val page = mapper.readValue<AccountsPage>(raw)
        assertThat(page.data).hasSize(1)
        assertThat(page.data.single().accountNumber).isEqualTo("123456789/0800")
    }

    // --- balances (the bare-array shape #2458 fixed) -------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountBalancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the customer owns an account with a balance")
        .uponReceiving("GET the balance of an owned account")
        .path(balancesPath(ACCOUNT_ID))
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        // A BARE array — not wrapped in {"balances": [...]}. This is the exact shape #2458 fixed;
        // pinning it as an object here would silently resurrect the bug this pact exists to guard.
        .body(
            LambdaDsl.newJsonArrayMinLike(1) { arr ->
                arr.`object` { o ->
                    o.stringType("currency", "CZK")
                    o.decimalType("bookedAmount", BigDecimal("1000.00"))
                    o.decimalType("availableAmount", BigDecimal("950.00"))
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountBalancePact")
    fun `getBalances parses the edge's bare-array balance shape`(mockServer: MockServer) {
        val path = balancesPath(ACCOUNT_ID)
        assertClientPathMatches(path, clientDerivedPath("getBalances", ACCOUNT_ID))

        val raw = get(mockServer, clientDerivedPath("getBalances", ACCOUNT_ID))

        val balances: List<BalanceDto> = mapper.readValue(raw)
        assertThat(balances).hasSize(1)
        assertThat(balances.single().currency).isEqualTo("CZK")
        assertThat(balances.single().availableAmount).isEqualByComparingTo(BigDecimal("950.00"))
    }

    // --- transactions ---------------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountTransactionsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the customer owns an account with transactions")
        .uponReceiving("GET the transactions of an owned account")
        .path(TRANSACTIONS_PATH)
        .query("accountId=$ACCOUNT_ID&limit=10")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("data") { a ->
                    a.`object` { t ->
                        t.decimalType("amount", BigDecimal("250.00"))
                        t.stringType("currencyCode", "CZK")
                        t.stringType("type", "PAYMENT")
                        t.stringType("status", "BOOKED")
                        t.stringType("bookingDate", "2026-07-01")
                        t.stringType("description", "Rent")
                    }
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountTransactionsPact")
    fun `listTransactions parses the edge's real transaction page shape`(mockServer: MockServer) {
        assertClientPathMatches(TRANSACTIONS_PATH, clientDerivedPath("listTransactions"))

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("accountId", ACCOUNT_ID)
            .queryParam("limit", 10)
            .get(clientDerivedPath("listTransactions"))
            .then()
            .statusCode(200)
            .extract().asString()

        val page = mapper.readValue<TransactionPage>(raw)
        assertThat(page.data).hasSize(1)
        assertThat(page.data.single().description).isEqualTo("Rent")
    }

    // --- fx rates ---------------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun fxRatesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("fx rates are available")
        .uponReceiving("GET the current FX rates")
        .path(FX_RATES_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            LambdaDsl.newJsonArrayMinLike(1) { arr ->
                arr.`object` { o ->
                    o.stringType("base", "EUR")
                    o.stringType("quote", "CZK")
                    o.stringType("rate", "25.0")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "fxRatesPact")
    fun `getFxRates parses the edge's projected rate list`(mockServer: MockServer) {
        assertClientPathMatches(FX_RATES_PATH, clientDerivedPath("getFxRates"))

        val raw = get(mockServer, clientDerivedPath("getFxRates"))

        val rates: List<FxRateDto> = mapper.readValue(raw)
        assertThat(rates).hasSize(1)
        assertThat(rates.single().base).isEqualTo("EUR")
    }

    // --- cards ---------------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun cardsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the customer has a card")
        .uponReceiving("GET the customer's cards")
        .path(CARDS_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            LambdaDsl.newJsonArrayMinLike(1) { arr ->
                arr.`object` { o ->
                    o.uuid("id", UUID.fromString("11111111-2222-4333-8444-555555555501"))
                    o.stringType("maskedPan", "**** **** **** 1234")
                    o.stringType("cardType", "DEBIT")
                    o.stringType("network", "VISA")
                    o.stringType("status", "ACTIVE")
                    o.stringType("expiryDate", "2029-12-31")
                    o.stringType("currency", "CZK")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardsPact")
    fun `listCards parses the edge's card list shape`(mockServer: MockServer) {
        assertClientPathMatches(CARDS_PATH, clientDerivedPath("listCards"))

        val raw = get(mockServer, clientDerivedPath("listCards"))

        val cards: List<CardDto> = mapper.readValue(raw)
        assertThat(cards).hasSize(1)
        assertThat(cards.single().network).isEqualTo("VISA")
    }

    // --- statements ---------------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun statementsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the customer owns an account with statements")
        .uponReceiving("GET the statements of an owned account")
        .path(statementsPath(ACCOUNT_ID))
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            LambdaDsl.newJsonArrayMinLike(1) { arr ->
                arr.`object` { o ->
                    o.uuid("id", UUID.fromString("11111111-2222-4333-8444-555555555502"))
                    o.stringType("pocketCurrency", "CZK")
                    o.stringType("periodFrom", "2026-06-01")
                    o.stringType("periodTo", "2026-06-30")
                    o.numberType("legalSequenceNumber", 7)
                    o.decimalType("openingBalance", BigDecimal("900.00"))
                    o.decimalType("closingBalance", BigDecimal("1000.00"))
                    o.numberType("entryCount", 12)
                    o.stringType("status", "ISSUED")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "statementsPact")
    fun `listStatements parses the edge's statement list shape`(mockServer: MockServer) {
        val path = statementsPath(ACCOUNT_ID)
        assertClientPathMatches(path, clientDerivedPath("listStatements", ACCOUNT_ID))

        val raw = get(mockServer, clientDerivedPath("listStatements", ACCOUNT_ID))

        val statements: List<StatementDto> = mapper.readValue(raw)
        assertThat(statements).hasSize(1)
        assertThat(statements.single().legalSequenceNumber).isEqualTo(7)
    }

    // --- standing orders --------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun standingOrdersPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the customer has a standing order")
        .uponReceiving("GET the customer's standing orders")
        .path(STANDING_ORDERS_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            LambdaDsl.newJsonArrayMinLike(1) { arr ->
                arr.`object` { o ->
                    o.uuid("id", UUID.fromString("11111111-2222-4333-8444-555555555503"))
                    o.stringType("creditorIban", "CZ6508000000192000145399")
                    o.stringType("creditorName", "Elektrárna a.s.")
                    o.stringType("status", "ACTIVE")
                    o.stringType("frequency", "MONTHLY")
                    o.decimalType("amount", BigDecimal("1500.0"))
                    o.stringType("currency", "CZK")
                    o.stringType("nextExecutionDate", "2026-08-01")
                    o.stringType("remittanceInfo", "Elektřina")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "standingOrdersPact")
    fun `listStandingOrders parses the edge's standing-order list shape`(mockServer: MockServer) {
        assertClientPathMatches(STANDING_ORDERS_PATH, clientDerivedPath("listStandingOrders"))

        val raw = get(mockServer, clientDerivedPath("listStandingOrders"))

        val orders: List<StandingOrderDto> = mapper.readValue(raw)
        assertThat(orders).hasSize(1)
        assertThat(orders.single().creditorName).isEqualTo("Elektrárna a.s.")
    }

    // --- shared helpers -----------------------------------------------------------------------

    private fun get(mockServer: MockServer, path: String): String = given()
        .baseUri(mockServer.getUrl())
        .accept("application/json")
        // Reflected off the client, NOT retyped: this is the request the real client issues.
        .get(path)
        .then()
        .statusCode(200)
        .extract().asString()

    /**
     * The asymmetry that makes this contract falsifiable at the consumer layer: the path the
     * client would really call, recomputed from [CustomerEdgeRestClient]'s own annotations, must
     * equal the LITERAL this pact promises customer-edge. A `@Path` edit on the client reddens
     * here.
     */
    private fun assertClientPathMatches(expected: String, actual: String) {
        assertThat(actual)
            .describedAs(
                "CustomerEdgeRestClient's @Path no longer produces the path this pact pins — " +
                    "either fix the client or update the literal *and* re-verify against customer-edge",
            )
            .isEqualTo(expected)
    }

    private companion object {
        const val CONSUMER = "openbank-copilot-service"
        const val PROVIDER = "openbank-customer-edge"

        const val ACCOUNT_ID = "33333333-3333-3333-3333-333333333333"

        // LITERAL — never derived from the client annotations (CLAUDE.md).
        const val ACCOUNTS_PATH = "/customer/v1/accounts"
        const val TRANSACTIONS_PATH = "/customer/v1/transactions"
        const val FX_RATES_PATH = "/customer/v1/fx/rates"
        const val CARDS_PATH = "/customer/v1/cards"
        const val STANDING_ORDERS_PATH = "/customer/v1/standing-orders"

        fun balancesPath(accountId: String) = "/customer/v1/balances/$accountId"

        fun statementsPath(accountId: String) = "/customer/v1/statements/$accountId"

        fun clientDerivedPath(methodName: String, vararg pathParams: String): String {
            val method = CustomerEdgeRestClient::class.java.methods.single { it.name == methodName }
            var path = method.getAnnotation(Path::class.java).value
            pathParams.forEach { value ->
                path = path.replaceFirst(Regex("\\{[^}]+}"), value)
            }
            return path
        }
    }
}
