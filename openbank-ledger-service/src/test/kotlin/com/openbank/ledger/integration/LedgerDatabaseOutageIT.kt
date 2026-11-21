// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresToxiproxyTestResource
import eu.rekawek.toxiproxy.ToxiproxyClient
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.config.HttpClientConfig.httpClientConfig
import io.restassured.config.RestAssuredConfig.config
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real network outage before a write, followed by recovery and idempotent HTTP replay. */
@QuarkusTest
@TestProfile(LedgerDatabaseOutageIT.OutageProfile::class)
class LedgerDatabaseOutageIT {
    class OutageProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresToxiproxyTestResource::class.java))
    }

    @ConfigProperty(name = "test.database-outage.direct-jdbc-url")
    lateinit var directJdbcUrl: String

    @ConfigProperty(name = "test.database-outage.proxy-admin-url")
    lateinit var proxyAdminUrl: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var databaseUser: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var databasePassword: String

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `database outage rejects writes and recovery preserves one atomic posting`() {
        val request = Posting()
        val payload = request.payload()
        val admin = URI.create(proxyAdminUrl)
        val proxy = ToxiproxyClient(admin.host, admin.port).getProxy("ledger-postgres")
        DriverManager.getConnection(directJdbcUrl, databaseUser, databasePassword).use { observer ->
            // Establish the healthy write path before injecting the fault. First-use HTTP/client
            // initialization must not consume the bound intended for rejecting an unavailable DB.
            assertThat(post(Posting().payload()).statusCode).isEqualTo(201)
            try {
                proxy.disable()
                assertThat(proxy.isEnabled).isFalse()
                val started = System.nanoTime()
                val rejected = post(payload)
                assertThat(rejected.statusCode).isBetween(500, 599)
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10))
                assertNoPosting(observer, request)
            } finally {
                proxy.enable()
            }

            val recovered = recover(payload)
            assertThat(recovered.header("Idempotent-Replayed")).isEqualTo("false")
            assertThat(recovered.jsonPath().getString("status")).isEqualTo("POSTED")
            assertThat(recovered.jsonPath().getString("transactionId")).isEqualTo(request.transactionId.toString())
            val journalId = UUID.fromString(recovered.jsonPath().getString("id"))
            val replay = post(payload)
            assertThat(replay.statusCode).isEqualTo(201)
            assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true")
            assertThat(replay.jsonPath().getString("id")).isEqualTo(journalId.toString())
            assertAtomicPosting(observer, request, journalId)
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @TestSecurity(user = "service-account-openbank-lending", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-lending"),
            Claim(key = "preferred_username", value = "service-account-openbank-lending"),
        ],
    )
    fun `a committed branch posting keeps one journal when its acknowledgement is lost`() {
        val transactionId = UUID.randomUUID()
        val reference = "loan:$transactionId:disbursement"
        val request = Posting(key = reference, transactionId = transactionId)
        val book = UUID.randomUUID()
        val otherBook = UUID.randomUUID()
        val payload = request.payload(book)
        val proxyAdmin = URI.create(proxyAdminUrl)
        val proxy = ToxiproxyClient(proxyAdmin.host, proxyAdmin.port).getProxy("ledger-postgres")
        DriverManager.getConnection(directJdbcUrl, databaseUser, databasePassword).use { observer ->
            assertThat(post(payload).statusCode).isEqualTo(409)
            registerBranchBooks(observer, book, otherBook)

            // The first caller loses its receipt. Only the database is used to identify the commit.
            post(payload)
            val journalId = rows(
                observer,
                "SELECT journal_id::text FROM ledger_idempotency WHERE idempotency_key = ?",
                reference,
            ).single()
            try {
                proxy.disable()
                assertThat(post(payload).statusCode).isBetween(500, 599)
            } finally {
                proxy.enable()
            }

            val receipt = recover(payload)
            assertThat(receipt.header("Idempotent-Replayed")).isEqualTo("true")
            assertThat(receipt.jsonPath().getString("id")).isEqualTo(journalId)
            assertThat(receipt.jsonPath().getString("branchBookId")).isEqualTo(book.toString())
            assertThat(post(request.payload(otherBook)).statusCode).isEqualTo(409)
            assertThat(post(payload.replace("25.00", "26.00")).statusCode).isEqualTo(409)
            assertThat(post(request.payload()).statusCode).isEqualTo(409)
            assertAtomicPosting(observer, request, UUID.fromString(journalId))
            val statement = """
                SELECT line.side || ':' || line.base_amount::text FROM journal_lines line
                    JOIN journal_entries entry ON entry.id = line.journal_id
                WHERE entry.branch_book_id = ? ORDER BY line.side
            """.trimIndent()
            val lines = rows(observer, statement, book)
            assertThat(lines.map { it.substringBefore(':') }).containsExactly("C", "D")
            assertThat(lines.map { it.substringAfter(':').toBigDecimal().stripTrailingZeros() })
                .containsExactly("25".toBigDecimal(), "25".toBigDecimal())
            assertThat(rows(observer, statement, otherBook)).isEmpty()
        }
    }

    private fun registerBranchBooks(observer: Connection, vararg books: UUID) {
        val sql = "INSERT INTO ledger_branch_books (establishment_id, bank_id, host_country) VALUES (?, ?, 'AT')"
        val bank = UUID.randomUUID()
        observer.prepareStatement(sql).use { statement ->
            books.forEach { book ->
                statement.setObject(1, book)
                statement.setObject(2, bank)
                statement.executeUpdate()
            }
        }
    }

    private fun post(payload: String): Response = given().config(
        config().httpClient(
            httpClientConfig().setParam("http.connection.timeout", 5000).setParam("http.socket.timeout", 5000),
        ),
    ).contentType("application/json").body(payload).post("/api/v1/journals")

    private fun recover(payload: String): Response {
        val started = System.nanoTime()
        val deadline = started + Duration.ofSeconds(25).toNanos()
        var response = post(payload)
        while (response.statusCode != 201 && System.nanoTime() < deadline) {
            assertThat(response.statusCode).describedAs("only transient server errors may recover").isBetween(500, 599)
            Thread.sleep(250)
            response = post(payload)
        }
        assertThat(response.statusCode).describedAs("database pool must recover within 30 seconds").isEqualTo(201)
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30))
        return response
    }

    private fun assertNoPosting(connection: Connection, request: Posting) {
        assertThat(rows(connection, JOURNALS, request.transactionId)).isEmpty()
        assertThat(rows(connection, IDEMPOTENCY, request.key)).isEmpty()
        assertThat(rows(connection, OUTBOX, request.transactionId.toString())).isEmpty()
    }

    private fun assertAtomicPosting(connection: Connection, request: Posting, journalId: UUID) {
        val journals = rows(connection, JOURNALS, request.transactionId)
        val idempotency = rows(connection, IDEMPOTENCY, request.key)
        val outbox = rows(connection, OUTBOX, request.transactionId.toString())
        assertThat(journals).hasSize(1)
        assertThat(idempotency).hasSize(1)
        assertThat(outbox).hasSize(2)
        val transaction = journals.single()
        assertThat(idempotency.single()).isEqualTo(transaction)
        assertThat(outbox).containsOnly(transaction)
        val journalIds = rows(
            connection,
            "select journal_id::text from ledger_idempotency where idempotency_key = ?",
            request.key,
        )
        assertThat(journalIds)
            .containsExactly(journalId.toString())
        val eventTypes = rows(
            connection,
            "select event_type from ledger_outbox where payload::jsonb ->> 'transactionId' = ?",
            request.transactionId.toString(),
        )
        assertThat(eventTypes)
            .containsExactlyInAnyOrder("JournalPosted", "AccountBookedChanged")
    }

    private fun rows(connection: Connection, sql: String, parameter: Any): List<String> =
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = 5
            statement.setObject(1, parameter)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }

    private data class Posting(
        val key: String = UUID.randomUUID().toString(),
        val transactionId: UUID = UUID.randomUUID(),
        val subAccountId: UUID = UUID.randomUUID(),
    ) {
        fun payload(branchBookId: UUID? = null): String = """
            {
              "idempotencyKey": "$key",
              "branchBookId": ${branchBookId?.let { "\"$it\"" } ?: "null"},
              "transactionId": "$transactionId",
              "entryDate": "${LocalDate.now()}",
              "valueDate": "${LocalDate.now()}",
              "description": "Database outage recovery test",
              "createdBy": "00000000-0000-0000-0000-000000000099",
              "lines": [
                {
                  "glAccountId": "a0000000-0000-0000-0000-000000000001",
                  "side": "DEBIT", "amount": "25.00", "currencyCode": "CZK",
                  "baseAmount": "25.00", "baseCurrencyCode": "CZK"
                },
                {
                  "glAccountId": "a0000000-0000-0000-0000-000000000002",
                  "side": "CREDIT", "amount": "25.00", "currencyCode": "CZK",
                  "baseAmount": "25.00", "baseCurrencyCode": "CZK", "subAccountId": "$subAccountId"
                }
              ]
            }
        """.trimIndent()
    }

    private companion object {
        const val JOURNALS = "select xmin::text from journal_entries where transaction_id = ?"
        const val IDEMPOTENCY = "select xmin::text from ledger_idempotency where idempotency_key = ?"
        const val OUTBOX = "select xmin::text from ledger_outbox where payload::jsonb ->> 'transactionId' = ?"
    }
}
