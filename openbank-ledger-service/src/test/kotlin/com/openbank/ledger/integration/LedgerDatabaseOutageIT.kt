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
        fun payload(): String = """
            {
              "idempotencyKey": "$key",
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
