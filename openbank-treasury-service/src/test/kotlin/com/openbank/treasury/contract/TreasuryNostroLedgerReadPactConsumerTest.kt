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
 * `LedgerNegativeAuthProviderVerificationTest` (the 401) in openbank-ledger-service — no Pact Broker
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
                        // the chart id of 1001) carries the seeded line.
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
     * equivalent interactions, replayed by ledger's `LedgerNegativeAuthProviderVerificationTest`
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

    /**
     * ADR-0341 D3 -- the WRITE call: treasury posts a journal whose legs name an accounting class and
     * role, never a GL account. The request body is pinned field by field (values literal, ids as
     * type matchers) and the test below serialises the PRODUCTION [PostJournalRequest] DTO against it,
     * so a drifted field name fails the consumer test while a drifted ledger fails the provider replay.
     * The provider state is the already-existing chart seed; the ledger resolves the classes through
     * V32's `accounting_class_gl_map`, so replay is also the proof the map covers what treasury sends.
     */
    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun postJournalByAccountingClassPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a treasury settlement journal naming accounting classes instead of GL accounts")
        .path("/api/v1/journals")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        .body(journalBody("INTERBANK_PLACEMENT", "PRINCIPAL"))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.stringValue("status", "POSTED")
            }.build(),
        )
        .toPact()

    /** A class the ledger has no mapping for is the ledger's own 422, never a guessed account. */
    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun postJournalWithUnmappedClassPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a treasury journal whose accounting class the ledger does not map")
        .path("/api/v1/journals")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        .body(journalBody("NOT_A_MAPPED_CLASS", "PRINCIPAL", key = "treasury:unmapped:settled"))
        .willRespondWith()
        .status(422)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringType("error", "no GL account is mapped for accountingClass=NOT_A_MAPPED_CLASS")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun rejectsJournalPostWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST a treasury journal with a missing or expired token")
        .path("/api/v1/journals")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        .body(journalBody("INTERBANK_PLACEMENT", "PRINCIPAL", key = "treasury:no-token:settled"))
        .willRespondWith()
        .status(401)
        .toPact()

    /**
     * ADR-0341 D3 -- the READ of the ledger's accountingClass -> GL map, which is where treasury now
     * learns which GL is a currency's nostro (it names no GL code itself). The row is pinned by value:
     * the nostro CZK row, its fixed chart id and its code.
     */
    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun accountingClassMapPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("GET the accountingClass to GL account map")
        .path("/api/v1/accounting-classes")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            au.com.dius.pact.consumer.dsl.PactDslJsonArray.arrayMinLike(1)
                .stringValue("accountingClass", "NOSTRO_ACCOUNT")
                .stringValue("role", "PRINCIPAL")
                .stringValue("currencyCode", "CZK")
                .stringValue("glAccountId", NOSTRO_CZK_GL_ID)
                .stringValue("glCode", "1001")
                .closeObject()!!,
        )
        .toPact()

    @Pact(consumer = "openbank-treasury-service", provider = "openbank-ledger-service")
    fun rejectsAccountingClassMapWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("GET the accountingClass map with a missing or expired token")
        .path("/api/v1/accounting-classes")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(401)
        .toPact()

    private fun journalBody(accountingClass: String, role: String, key: String = JOURNAL_KEY) = newJsonBody { o ->
        o.stringValue("idempotencyKey", key)
        o.uuid("transactionId")
        o.stringValue("entryDate", JOURNAL_DATE)
        o.stringValue("valueDate", JOURNAL_DATE)
        o.stringType("description", "MM placement settlement")
        o.uuid("createdBy")
        o.array("lines") { lines ->
            lines.`object` { l -> journalLeg(l, accountingClass, role, "DEBIT") }
            lines.`object` { l -> journalLeg(l, "NOSTRO_ACCOUNT", "PRINCIPAL", "CREDIT") }
        }
    }.build()

    private fun journalLeg(
        l: au.com.dius.pact.consumer.dsl.LambdaDslObject,
        accountingClass: String,
        role: String,
        side: String,
    ) {
        l.stringValue("accountingClass", accountingClass)
        l.stringValue("accountingRole", role)
        l.stringValue("side", side)
        l.decimalType("amount", 250000.00)
        l.stringValue("currencyCode", "CZK")
        l.nullValue("fxRate")
        l.decimalType("baseAmount", 250000.00)
        l.stringValue("baseCurrencyCode", "CZK")
    }

    private fun postBody(accountingClass: String, key: String = JOURNAL_KEY): String {
        fun leg(cls: String, role: String, side: String) =
            com.openbank.treasury.infrastructure.client.JournalLineRequest(
                glAccountId = null,
                accountingClass = cls,
                accountingRole = role,
                side = side,
                amount = java.math.BigDecimal("250000.00"),
                currencyCode = "CZK",
                fxRate = null,
                baseAmount = java.math.BigDecimal("250000.00"),
                baseCurrencyCode = "CZK",
            )
        return com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            com.openbank.treasury.infrastructure.client.PostJournalRequest(
                idempotencyKey = key,
                transactionId = java.util.UUID.fromString("0191c0de-0000-7000-8000-000000000001"),
                entryDate = JOURNAL_DATE,
                valueDate = JOURNAL_DATE,
                description = "MM placement settlement",
                lines = listOf(
                    leg(accountingClass, "PRINCIPAL", "DEBIT"),
                    leg("NOSTRO_ACCOUNT", "PRINCIPAL", "CREDIT"),
                ),
                createdBy = java.util.UUID.fromString("0191c0de-0000-7000-8000-0000000000aa"),
            ),
        )
    }

    private fun clientDerivedPostPath(): String = clientDerivedJournalsPath()

    @Test
    @PactTestFor(pactMethod = "accountingClassMapPact")
    fun `the accounting class map names the nostro GL of a currency`(mockServer: MockServer) {
        val rows = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get("/api/v1/accounting-classes")
            .then()
            .statusCode(200)
            .extract().jsonPath()
        assertThat(rows.getString("[0].glCode")).isEqualTo("1001")
        assertThat(rows.getString("[0].glAccountId")).isEqualTo(NOSTRO_CZK_GL_ID)
        // the production client reads exactly this route
        assertThat(
            com.openbank.treasury.infrastructure.nostro.LedgerAccountingClassRestClient::class.java
                .getAnnotation(jakarta.ws.rs.Path::class.java).value,
        ).isEqualTo("/api/v1/accounting-classes")
    }

    @Test
    @PactTestFor(pactMethod = "rejectsAccountingClassMapWithMissingToken")
    fun `the accounting class map is refused with 401 when the caller has no valid identity`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get("/api/v1/accounting-classes")
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "postJournalByAccountingClassPact")
    fun `a journal naming accounting classes is created with the production request DTO`(mockServer: MockServer) {
        assertThat(clientDerivedPostPath()).isEqualTo("/api/v1/journals")
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .accept("application/json")
            .body(postBody("INTERBANK_PLACEMENT"))
            .post(clientDerivedPostPath())
            .then()
            .statusCode(201)
            .extract().jsonPath()
        assertThat(body.getString("status")).isEqualTo("POSTED")
    }

    @Test
    @PactTestFor(pactMethod = "postJournalWithUnmappedClassPact")
    fun `an unmapped accounting class is the ledger's 422`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .accept("application/json")
            .body(postBody("NOT_A_MAPPED_CLASS", key = "treasury:unmapped:settled"))
            .post(clientDerivedPostPath())
            .then()
            .statusCode(422)
    }

    @Test
    @PactTestFor(pactMethod = "rejectsJournalPostWithMissingToken")
    fun `a journal post is refused with 401 when the caller has no valid identity`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .accept("application/json")
            .body(postBody("INTERBANK_PLACEMENT", key = "treasury:no-token:settled"))
            .post(clientDerivedPostPath())
            .then()
            .statusCode(401)
    }

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
        const val JOURNAL_DATE = "2026-03-16"
        const val JOURNAL_KEY = "treasury:0191c0de-0000-7000-8000-000000000001:settled"

        // the chart id of GL 1001 — CZK nostro, seeded by ledger's
        // V29__treasury_money_market_accounts.sql.
        const val NOSTRO_CZK_GL_ID = "a0000000-0000-0000-0000-000000001001"
    }
}
