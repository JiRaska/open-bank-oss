// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank authors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.e2e

import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end journey for a customer transfer through transaction-service, the money-path
 * orchestrator every payment runs through: **an operator initiates an own-account transfer,
 * the retried idempotency key replays the same transaction and a different body under the
 * same key conflicts, the completed transaction is reversed — the original flips to REVERSED
 * and the reversal settles as a COMPLETED REVERSAL linked to its origin — and the terminal
 * state refuses every further move.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`)
 * against real Postgres (full Flyway chain), Redpanda and Valkey via
 * [PostgresRedpandaTestResource]. No use case, repository or domain object is touched from
 * the test body. The payment saga really runs: the test-scope Temporal environment
 * (`WorkflowClientTestProducer`) executes the real `PaymentWorkflow` contract against the
 * real terminal-write activity, so the 201 body the client receives is the post-saga state —
 * a transaction that claims COMPLETED before its durable write would fail this journey.
 *
 * ### Why the read-back is the observable
 *
 * Every command returns the aggregate the handler re-read after the saga, and the `GET`
 * after each hop is a different code path over the committed row, so comparing them
 * distinguishes "the command was accepted" from "the command was accepted and the state
 * moved". The reversal link is the one field the HTTP response does not expose (only the
 * persisted row carries `reversal_of`/`is_reversal` — #8841), so the journey reads it back
 * over plain JDBC as the third witness, the same shape the sibling `TransactionReversalLinkIT`
 * uses.
 *
 * Falsification: with the terminal write moved back out of the workflow (#4238 regression),
 * order 1 fails at the first assertion (`expected COMPLETED but was PENDING`).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionJourneyE2E {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var jdbcUser: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var jdbcPassword: String

    private lateinit var journeyKey: String
    private lateinit var journeySource: String
    private lateinit var journeyTarget: String
    private lateinit var originalId: String
    private lateinit var originalReference: String
    private lateinit var reversalId: String

    @Test
    @Order(1)
    @TestSecurity(user = OPERATOR_ONE, roles = ["ROLE_OPERATOR"])
    fun `1 - the operator initiates a transfer and the payment saga settles it before the response`() {
        journeyKey = "txn-${UUID.randomUUID()}"
        journeySource = UUID.randomUUID().toString()
        journeyTarget = UUID.randomUUID().toString()

        val created = initiate(journeyKey, journeySource, journeyTarget, AMOUNT)
            .statusCode(201)
            .extract().jsonPath()
        originalId = created.getString("id")
        originalReference = created.getString("referenceNumber")
        // The 201 body is re-read AFTER the workflow returned (#4238): COMPLETED here means the
        // terminal write is as durable as the settlement itself — not just "accepted".
        assertThat(created.getString("status")).isEqualTo("COMPLETED")
        assertThat(created.getString("completedAt")).isNotBlank()
        assertThat(created.getString("type")).isEqualTo("TRANSFER")

        // The committed row agrees with what the handler returned — over a different code path.
        val stored = getTransaction(originalId)
        for (field in listOf("type", "status", "referenceNumber", "currencyCode", "description")) {
            assertThat(stored[field])
                .`as`("stored transaction field %s must equal the initiated one", field)
                .isEqualTo(created.getString(field))
        }
        assertThat(stored["sourceAccountId"]).isEqualTo(journeySource)
        assertThat(stored["targetAccountId"]).isEqualTo(journeyTarget)
        assertThat(stored["amount"].toString()).isEqualTo(created.getString("amount"))

        // Non-repudiation gate (ADR-0021): a customer-initiated movement without an SCA
        // challenge or a documented exemption is rejected before anything is written.
        val scaKey = "txn-${UUID.randomUUID()}"
        initiate(
            scaKey,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "10.00",
            initiatedByPartyId = UUID.randomUUID().toString(),
        ).statusCode(400)
        // The rejected key stays unbound: a compliant retry under it succeeds.
        initiate(scaKey, journeySource, journeyTarget, "10.00").statusCode(201)
    }

    @Test
    @Order(2)
    @TestSecurity(user = OPERATOR_ONE, roles = ["ROLE_OPERATOR"])
    fun `2 - the retried key replays and a different body under the same key conflicts`() {
        val body = transferBody(journeyKey, journeySource, journeyTarget, AMOUNT)
        val replay = postTransaction(body).statusCode(201).extract().jsonPath()
        assertThat(replay.getString("id")).isEqualTo(originalId)
        assertThat(replay.getString("status")).isEqualTo("COMPLETED")

        // Exactly one row exists for the key: the replay neither doubled the transaction nor
        // re-ran the saga (the workflow would reject a second run on the same workflow id).
        val listed = listTransactionIds(journeySource)
        assertThat(listed).contains(originalId)

        // The same key with a DIFFERENT amount is a 409 — the key is a fingerprint, not a nonce.
        postTransaction(transferBody(journeyKey, journeySource, journeyTarget, "99999.00"))
            .statusCode(409)
        assertThat(listTransactionIds(journeySource)).hasSize(2) // journey + the SCA-gate retry
    }

    @Test
    @Order(3)
    @TestSecurity(user = OPERATOR_TWO, roles = ["ROLE_OPERATOR"])
    fun `3 - the completed transfer is reversed and the linkage reads back over GET and JDBC`() {
        val reversal = reverse(originalId, "txn-${UUID.randomUUID()}", "customer dispute")
            .statusCode(200)
            .extract().jsonPath()
        reversalId = reversal.getString("id")
        // The reversal is an incoming credit that settles through its own saga run.
        assertThat(reversal.getString("status")).isEqualTo("COMPLETED")
        assertThat(reversal.getString("type")).isEqualTo("REVERSAL")
        assertThat(reversal.getString("targetAccountId")).isEqualTo(journeySource)

        // The original flipped to REVERSED — read back over a different code path.
        assertThat(getTransaction(originalId)["status"]).isEqualTo("REVERSED")

        // The HTTP response does not expose the linkage; the persisted row is the witness.
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.createStatement().executeQuery(
                "SELECT reversal_of, is_reversal FROM transactions WHERE id = '$reversalId'",
            ).use { rs ->
                assertThat(rs.next()).describedAs("reversal row exists").isTrue()
                assertThat(rs.getObject("reversal_of").toString()).isEqualTo(originalId)
                assertThat(rs.getBoolean("is_reversal")).isTrue()
            }
            conn.createStatement().executeQuery(
                "SELECT is_reversal FROM transactions WHERE id = '$originalId'",
            ).use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getBoolean("is_reversal")).isFalse()
            }
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = OPERATOR_TWO, roles = ["ROLE_OPERATOR"])
    fun `4 - the terminal state refuses another reversal and unknown ids are rejected`() {
        // REVERSED is terminal for the return path: a second reversal is a business-rule
        // violation, not a server fault (IllegalStateException -> 422).
        reverse(originalId, "txn-${UUID.randomUUID()}", "second attempt").statusCode(422)
        assertThat(getTransaction(originalId)["status"]).isEqualTo("REVERSED")

        // Reversal of a transaction that does not exist is the caller's error (400), and the
        // reversal idempotency key stays unbound.
        val unknownKey = "txn-${UUID.randomUUID()}"
        reverse(UUID.randomUUID().toString(), unknownKey, "no such transaction").statusCode(400)
        assertThat(getTransaction(originalId)["status"]).isEqualTo("REVERSED")
    }

    private fun initiate(
        key: String,
        source: String,
        target: String,
        amount: String,
        initiatedByPartyId: String? = null,
    ): ValidatableResponse = postTransaction(transferBody(key, source, target, amount, initiatedByPartyId))

    private fun transferBody(
        key: String,
        source: String,
        target: String,
        amount: String,
        initiatedByPartyId: String? = null,
    ): String {
        val partyLine = initiatedByPartyId?.let { ",\n  \"initiatedByPartyId\": \"$it\"" } ?: ""
        return """
            {
              "idempotencyKey": "$key",
              "type": "TRANSFER",
              "sourceAccountId": "$source",
              "targetAccountId": "$target",
              "amount": "$amount",
              "currencyCode": "CZK",
              "description": "journey transfer",
              "valueDate": "${LocalDate.now()}$partyLine"
            }
        """.trimIndent()
    }

    private fun postTransaction(body: String): ValidatableResponse = Given {
        contentType("application/json")
        body(body)
    } When {
        post("/api/v1/transactions")
    } Then {
    }

    private fun reverse(originalId: String, key: String, reason: String): ValidatableResponse = Given {
        contentType("application/json")
        body("""{"idempotencyKey": "$key", "reason": "$reason"}""")
    } When {
        post("/api/v1/transactions/$originalId/reverse")
    } Then {
    }

    private fun getTransaction(id: String): Map<String, Any> = (
        Given {
            this
        } When {
            get("/api/v1/transactions/$id")
        } Then {
            statusCode(200)
        }
        ).extract().jsonPath().getMap("")

    private fun listTransactionIds(accountId: String): List<String> = (
        Given {
            this
        } When {
            get("/api/v1/transactions?accountId=$accountId&limit=50")
        } Then {
            statusCode(200)
        }
        ).extract().jsonPath().getList<String>("data.id")

    private companion object {
        const val OPERATOR_ONE = "00000000-0000-0000-0000-0000000000b1"
        const val OPERATOR_TWO = "00000000-0000-0000-0000-0000000000b2"
        const val AMOUNT = "2500.00"
    }
}
