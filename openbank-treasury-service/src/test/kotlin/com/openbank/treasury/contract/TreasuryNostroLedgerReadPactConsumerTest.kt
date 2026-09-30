// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.treasury.infrastructure.nostro.LedgerReadAdapter
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the READ calls `openbank-treasury-service` makes against
 * ledger-service's journal API for nostro reconciliation (ADR-0315 D5, #10896,
 * `LedgerReadRestClient` in `infrastructure/nostro/LedgerReadClient.kt`). PR #11052 wired these
 * calls to a real `RestClient` and ran them only against a fake ledger — nothing on the wire had
 * ever been checked against the real provider. This pins the two reads
 * [com.openbank.treasury.infrastructure.nostro.LedgerReadAdapter.nostroLines] and
 * [com.openbank.treasury.infrastructure.nostro.LedgerReadAdapter.accountBalance] actually issue, plus
 * the negative-auth case ADR-0279 requires on every changed contract test.
 *
 * The generated pact file is committed to `pacts/` (git-pact, ADR-0063) and replayed by
 * `LedgerPactProviderVerificationTest` (positive states) and
 * `LedgerNegativeAuthPactVerificationTest` (the 401) in openbank-ledger-service — no Pact Broker
 * involved, both always run.
 *
 * IMPORTANT — regenerate on change: if this test's `@Pact` methods change, re-run
 * (`./gradlew :openbank-treasury-service:test --tests
 * "*TreasuryNostroLedgerReadPactConsumerTest*"`) and commit the updated
 * `pacts/openbank-treasury-service-openbank-ledger-service.json` in the same PR —
 * `pact-drift-check.yml` fails otherwise.
 *
 * Paths are LITERAL on the interaction side (`.path("/api/v1/journals")`,
 * `.path("/api/v1/journals/accounts/1002/balance")`) per `CLAUDE.md`'s Pact section: deriving the
 * expected path from the client's own `@Path` would make the test vacuous against a client
 * pointed at a route that does not exist (finrep-service's `/api/v1/ledger/trial-balance`, #2269).
 * The REQUEST that is actually sent is reflected off the client instead
 * ([clientDerivedJournalsPath], [clientDerivedBalancePath]).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class TreasuryNostroLedgerReadPactConsumerTest {

    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun nostroJournalLinesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has a nostro journal line on 1001 for the statement date")
        .uponReceiving("GET journal entries for the CZK nostro GL window on the statement date")
        .path("/api/v1/journals")
        .query("fromDate=$STATEMENT_DATE&toDate=$STATEMENT_DATE&limit=$PAGE_SIZE")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.minArrayLike("data", 1) { j ->
                    j.uuid("id")
                    j.uuid("transactionId")
                    // stringValue, NOT stringType: the window is [statementDate, statementDate], so
                    // an entry NOT dated the statement date is itself the defect this pins against.
                    j.stringValue("entryDate", STATEMENT_DATE)
                    j.stringType("description", "MM placement settlement")
                    // stringValue: nostroLines() only keeps entries whose status is in
                    // {POSTED, REVERSED} (LedgerReadAdapter.BOOKED) — a PENDING entry here would be
                    // silently dropped by the client, so the fixture pins the case that must count.
                    j.stringValue("status", "POSTED")
                    j.booleanType("synthetic", false)
                    j.minArrayLike("lines", 1) { l ->
                        l.uuid("id")
                        // stringValue: this interaction's whole point is that GL 1001 (CZK nostro,
                        // TreasuryChart.glAccountId("1001")) carries the seeded line.
                        l.stringValue("glAccountId", NOSTRO_CZK_GL_ID)
                        l.stringValue("side", "CREDIT")
                        l.decimalType("amount", 250000.00)
                        l.stringValue("currencyCode", "CZK")
                    }
                }
                o.`object`("pagination") { p ->
                    p.integerType("limit", PAGE_SIZE)
                    // booleanType, not a literal: the fixture seeds exactly one journal so this is
                    // false today, but pinning the type (not the value) is what the balance-service
                    // trial-balance pact already does for the same reason — the shape is the
                    // contract, not incidental fixture cardinality.
                    p.booleanType("hasNextPage", false)
                }
            }.build(),
        )
        .toPact()

    /**
     * The native-currency balance read (#11107): treasury asks for a nostro's balance in the
     * STATEMENT currency, so a EUR nostro is compared on EUR, never on the CZK `base_amount` the
     * trial balance aggregates. Path is the LITERAL `/api/v1/journals/accounts/1002/balance`; the
     * echoed code/currency/asOf are pinned by value because the adapter refuses a balance whose
     * echo differs from what it asked for.
     */
    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun nostroNativeBalancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has a EUR journal line on 1002 for the statement date")
        .uponReceiving("GET the EUR nostro GL balance in EUR as of the statement date")
        .path("/api/v1/journals/accounts/1002/balance")
        .query("asOf=$STATEMENT_DATE&currency=EUR")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("code", "1002")
                o.stringValue("currency", "EUR")
                o.stringValue("asOf", STATEMENT_DATE)
                o.stringType("scope", "REAL_ONLY")
                o.decimalType("debit", 10000.00)
                o.decimalType("credit", 0.00)
                o.decimalType("net", 10000.00)
            }.build(),
        )
        .toPact()

    /**
     * The ONE 404 treasury reads as "the ledger does not hold this GL" (#11113 review). The body is
     * pinned by VALUE: treasury matches `error == "GL account <code> not found"` exactly, and every
     * other 404 — a ledger that does not serve the route — is an upstream failure there. If the
     * ledger ever rewords this message, provider replay goes red before treasury blanks balances.
     */
    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun unknownGlAccountBalancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger does not hold GL account 9999")
        .uponReceiving("GET the balance of a GL account the ledger does not hold")
        .path("/api/v1/journals/accounts/9999/balance")
        .query("asOf=$STATEMENT_DATE&currency=EUR")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(404)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { o -> o.stringValue("error", "GL account 9999 not found") }.build())
        .toPact()

    /**
     * ADR-0279: every changed contract test needs a 401 negative interaction. treasury's `m2m`
     * identity can be missing, expired or revoked independently of the request otherwise being
     * identical to [nostroJournalLinesPact] — this pins that ledger still answers 401 (not a
     * silent 200 with stale/empty data) when it is. Shares `NEGATIVE_AUTH_STATE`
     * ("no valid M2M identity is presented") with sdd-/swift-/interest-/sepa-/transaction-service's
     * equivalent interactions, replayed by ledger's `LedgerNegativeAuthPactVerificationTest`
     * (no `@TestSecurity` on that class — the state IS the absence of an identity).
     */
    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun rejectsJournalListWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("GET the nostro journal window with a missing or expired token")
        .path("/api/v1/journals")
        .query("fromDate=$STATEMENT_DATE&toDate=$STATEMENT_DATE&limit=$PAGE_SIZE")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "nostroJournalLinesPact")
    fun `journal list returns the seeded nostro line for the statement date`(mockServer: MockServer) {
        assertThat(clientDerivedJournalsPath()).isEqualTo("/api/v1/journals")

        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("fromDate", STATEMENT_DATE)
            .queryParam("toDate", STATEMENT_DATE)
            .queryParam("limit", PAGE_SIZE)
            .get(clientDerivedJournalsPath())
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("data[0].entryDate")).isEqualTo(STATEMENT_DATE)
        assertThat(body.getString("data[0].status")).isEqualTo("POSTED")
        assertThat(body.getString("data[0].lines[0].glAccountId")).isEqualTo(NOSTRO_CZK_GL_ID)
        assertThat(body.getString("data[0].lines[0].currencyCode")).isEqualTo("CZK")
        assertThat(body.getBoolean("pagination.hasNextPage")).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "nostroNativeBalancePact")
    fun `native balance of the EUR nostro is stated in EUR for the statement date`(mockServer: MockServer) {
        assertThat(clientDerivedBalancePath("1002")).isEqualTo("/api/v1/journals/accounts/1002/balance")

        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("asOf", STATEMENT_DATE)
            .queryParam("currency", "EUR")
            .get(clientDerivedBalancePath("1002"))
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("code")).isEqualTo("1002")
        assertThat(body.getString("currency")).isEqualTo("EUR")
        assertThat(body.getString("asOf")).isEqualTo(STATEMENT_DATE)
        assertThat(body.getDouble("net")).isEqualTo(10000.00)
    }

    @Test
    @PactTestFor(pactMethod = "unknownGlAccountBalancePact")
    fun `an unknown GL account is ledger's own 404 body, the one treasury reads as not held`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("asOf", STATEMENT_DATE)
            .queryParam("currency", "EUR")
            .get(clientDerivedBalancePath("9999"))
            .then()
            .statusCode(404)
            .extract().asString()

        assertThat(LedgerReadAdapter.isUnknownAccount(body, "9999")).isTrue()
        assertThat(LedgerReadAdapter.isUnknownAccount(body, "1002")).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "rejectsJournalListWithMissingToken")
    fun `journal list is refused with 401 when the caller has no valid identity`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("fromDate", STATEMENT_DATE)
            .queryParam("toDate", STATEMENT_DATE)
            .queryParam("limit", PAGE_SIZE)
            .get(clientDerivedJournalsPath())
            .then()
            .statusCode(401)
    }

    /** The literal path `LedgerReadRestClient` issues — reflected off its own `@Path`, never retyped. */
    private fun clientDerivedJournalsPath(): String =
        com.openbank.treasury.infrastructure.nostro.LedgerReadRestClient::class.java
            .getAnnotation(jakarta.ws.rs.Path::class.java).value

    /** `LedgerReadRestClient.accountBalance`'s `@Path`, reflected and filled with [code]. */
    private fun clientDerivedBalancePath(code: String): String {
        val base = clientDerivedJournalsPath()
        val sub = com.openbank.treasury.infrastructure.nostro.LedgerReadRestClient::class.java
            .getMethod("accountBalance", String::class.java, String::class.java, String::class.java)
            .getAnnotation(jakarta.ws.rs.Path::class.java).value
        return "$base$sub".replace("{code}", code)
    }

    private companion object {
        const val STATEMENT_DATE = "2026-03-15"
        const val PAGE_SIZE = 200

        // TreasuryChart.glAccountId("1001") — CZK nostro, seeded by ledger's
        // V29__treasury_money_market_accounts.sql.
        const val NOSTRO_CZK_GL_ID = "a0000000-0000-0000-0000-000000001001"
    }
}
