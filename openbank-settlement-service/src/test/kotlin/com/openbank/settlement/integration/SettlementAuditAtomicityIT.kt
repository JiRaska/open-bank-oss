// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.settlement.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SettlementAuditAtomicityIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var objectMapper: ObjectMapper

    @Test
    @TestSecurity(user = "test-settlement-operator", roles = ["ROLE_OPERATOR"])
    fun `origination commits its state and audit event in the same transaction`() {
        val payer = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        val id = given().contentType("application/json").body(
            mapOf(
                "idempotencyKey" to key,
                "payerAccountId" to payer,
                "payeeAccountId" to payee,
                "amount" to "321.45",
                "currency" to "CZK",
            ),
        ).post("/api/v1/settlements").then().statusCode(201).extract().path<String>("id")
        val rows = auditRows(UUID.fromString(id))
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.auditTransaction).isEqualTo(row.settlementTransaction)
        val event = objectMapper.readTree(row.payload)
        assertThat(event.path("aggregateId").asText()).isEqualTo(id)
        assertThat(event.path("eventId").asText()).isEqualTo(row.eventId.toString())
        assertThat(event.path("sourceService").asText()).isEqualTo("settlement-service")
        assertThat(event.path("status").asText()).isEqualTo("PENDING")
        assertThat(BigDecimal(event.path("amount").asText())).isEqualByComparingTo("321.45")
        assertThat(event.path("payerAccountId").asText()).isEqualTo(payer.toString())
        assertThat(event.path("payeeAccountId").asText()).isEqualTo(payee.toString())
    }

    @Test
    @TestSecurity(user = "test-settlement-operator", roles = ["ROLE_OPERATOR"])
    fun `failed audit insert rolls back origination`() {
        val payer = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        rejectingAudit("payload::jsonb->>'payerAccountId'", payer.toString()) {
            originate(key, payer, 409)
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM settlements WHERE payer_account_id = ?").use { query ->
                query.setObject(1, payer)
                query.executeQuery().use { rows ->
                    rows.next()
                    assertThat(rows.getInt(1)).isZero()
                }
            }
        }
        val id = UUID.fromString(originate(key, payer))
        assertThat(auditRows(id)).hasSize(1)
    }

    @Test
    @TestSecurity(user = "test-settlement-operator", roles = ["ROLE_OPERATOR"])
    fun `failed audit insert rolls back status and retry writes one event`() {
        val id = UUID.fromString(originate())
        rejectingAudit("aggregate_id::text", id.toString()) {
            given().post("/test/settlement-audit/$id/status/BOOKED").then().statusCode(409)
        }
        assertThat(auditRows(id)).hasSize(1)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT status FROM settlements WHERE id = ?").use { query ->
                query.setObject(1, id)
                query.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("PENDING")
                }
            }
        }
        repeat(2) { given().post("/test/settlement-audit/$id/status/BOOKED").then().statusCode(200) }
        val events = auditRows(id)
        assertThat(events).hasSize(2)
        val booked = events.single { objectMapper.readTree(it.payload).path("status").asText() == "BOOKED" }
        assertThat(booked.auditTransaction).isEqualTo(booked.settlementTransaction)
        assertThat(objectMapper.readTree(booked.payload).path("previousStatus").asText()).isEqualTo("PENDING")
    }

    @Test
    @TestSecurity(user = "test-settlement-operator", roles = ["ROLE_OPERATOR"])
    fun `parallel claims emit exactly one transition and suppressed writes emit nothing`() {
        val id = UUID.fromString(originate())
        val barrier = CyclicBarrier(4)
        val claims = (1..4).map {
            CompletableFuture.supplyAsync {
                barrier.await()
                given().post("/test/settlement-audit/$id/claim").then().statusCode(200)
                    .extract().path<Boolean>("claimed")
            }
        }.map { it.join() }
        assertThat(claims.count { it }).isEqualTo(1)
        assertThat(auditRows(id)).hasSize(2)
        given().post("/test/settlement-audit/$id/status/BALANCE_STATE_UNKNOWN").then().statusCode(200)
        given().post("/test/settlement-audit/$id/status/CREDITED").then().statusCode(200)
        val statuses = auditRows(id).map { objectMapper.readTree(it.payload).path("status").asText() }
        assertThat(statuses).containsExactly("PENDING", "DEBITED", "BALANCE_STATE_UNKNOWN")
    }

    @Test
    @TestSecurity(user = "test-settlement-operator", roles = ["ROLE_OPERATOR"])
    fun `exhausted audit retries remain visible to the age alert`() {
        val id = UUID.fromString(originate())
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE settlement_outbox SET status = 'DEAD', created_at = now() - interval '1 hour' " +
                    "WHERE aggregate_id = ?",
            ).use { query ->
                query.setObject(1, id)
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
        }
        val age = given().post("/test/settlement-audit/refresh-audit-age").then().statusCode(200)
            .extract().path<Number>("age").toDouble()
        assertThat(age).isGreaterThanOrEqualTo(3500.0)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE settlement_outbox SET status = 'SENT' WHERE aggregate_id = ?",
            ).use { query ->
                query.setObject(1, id)
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
        }
        val cleared = given().post("/test/settlement-audit/refresh-audit-age").then().statusCode(200)
            .extract().path<Number>("age").toDouble()
        assertThat(cleared).isLessThan(age - 60.0)
    }

    @Test
    @TestSecurity(user = "test-settlement-operator", roles = ["ROLE_OPERATOR"])
    fun `origination audit preserves the accepted decimal value`() {
        val response = given().contentType("application/json").body(
            mapOf(
                "idempotencyKey" to UUID.randomUUID().toString(),
                "payerAccountId" to UUID.randomUUID(),
                "payeeAccountId" to UUID.randomUUID(),
                "amount" to "321.456800",
                "currency" to "CZK",
            ),
        ).post("/api/v1/settlements").then().statusCode(201).extract().asString()
        val accepted = objectMapper.readTree(response)
        val id = UUID.fromString(accepted.path("id").asText())
        val storedAmount = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT amount FROM settlements WHERE id = ?").use { query ->
                query.setObject(1, id)
                query.executeQuery().use { rows ->
                    rows.next()
                    rows.getBigDecimal(1)
                }
            }
        }
        assertThat(storedAmount).isEqualByComparingTo("321.4568")
        assertThat(BigDecimal(accepted.path("amount").asText())).isEqualByComparingTo(storedAmount)
        val event = objectMapper.readTree(auditRows(id).single().payload)
        assertThat(BigDecimal(event.path("amount").asText())).isEqualByComparingTo(storedAmount)
    }

    private fun originate(
        key: String = UUID.randomUUID().toString(),
        payer: UUID = UUID.randomUUID(),
        expectedStatus: Int = 201,
    ): String = given().contentType("application/json").body(
        mapOf(
            "idempotencyKey" to key,
            "payerAccountId" to payer,
            "payeeAccountId" to UUID.randomUUID(),
            "amount" to "321.45",
            "currency" to "CZK",
        ),
    ).post("/api/v1/settlements").then().statusCode(expectedStatus).extract().response().let {
        if (expectedStatus == 201) it.path("id") else ""
    }

    private fun rejectingAudit(expression: String, value: String, action: () -> Unit) {
        // Test-only constraint rejects just this aggregate. Existing evidence remains untouched.
        val name = "test_reject_" + UUID.randomUUID().toString().replace("-", "")
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    "ALTER TABLE settlement_outbox ADD CONSTRAINT $name CHECK (($expression) <> '$value') NOT VALID",
                )
            }
        }
        try {
            action()
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("ALTER TABLE settlement_outbox DROP CONSTRAINT $name") }
            }
        }
    }

    private fun auditRows(id: UUID): List<AuditRow> = dataSource.connection.use { connection ->
        val tableExists = connection.createStatement().use { query ->
            query.executeQuery("SELECT to_regclass('settlement_outbox') IS NOT NULL").use { rows ->
                rows.next()
                rows.getBoolean(1)
            }
        }
        if (!tableExists) return emptyList()
        connection.prepareStatement(
            "SELECT s.xmin::text, o.xmin::text, o.event_id, o.payload FROM settlements s " +
                "JOIN settlement_outbox o ON o.aggregate_id = s.id WHERE s.id = ? ORDER BY o.id",
        ).use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            AuditRow(
                                rows.getString(1),
                                rows.getString(2),
                                rows.getObject(3, UUID::class.java),
                                rows.getString(4),
                            ),
                        )
                    }
                }
            }
        }
    }

    private data class AuditRow(
        val settlementTransaction: String,
        val auditTransaction: String,
        val eventId: UUID,
        val payload: String,
    )
}
