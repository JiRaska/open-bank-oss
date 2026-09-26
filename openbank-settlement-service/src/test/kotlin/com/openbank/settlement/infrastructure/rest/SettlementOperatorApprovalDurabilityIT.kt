// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.settlement.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.PendingApproval
import com.openbank.settlement.it.PostgresTestResource
import com.openbank.settlement.it.SettlementOpaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlin.io.path.readText

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(SettlementOpaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(SettlementFourEyesProfile::class)
@TestSecurity(user = "durability-checker", roles = ["ROLE_OPERATOR"])
@OptIn(ExperimentalCoroutinesApi::class)
class SettlementOperatorApprovalDurabilityIT {
    private val instruction = CreateSettlementRequest(
        "durability-${UUID.randomUUID()}",
        UUID.randomUUID(),
        UUID.randomUUID(),
        java.math.BigDecimal("40"),
        "CZK",
    )

    @Inject lateinit var store: ApprovalStore

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var mapper: ObjectMapper

    @Test
    fun `the checker decision and authorization claim retain distinct audit facts`() {
        val before = java.time.OffsetDateTime.now()
        val approval = create()
        decide(approval.id, 200)
        assertThat(onContext { store.markExecuted(approval.id) }?.status).isEqualTo(ApprovalStatus.EXECUTED)
        val events = events(approval.id)
        assertThat(events.map { it["status"].asText() }).containsExactly("PENDING", "APPROVED", "EXECUTED")
        assertThat(events.map { it["eventId"].asText() }.distinct()).hasSize(3)
        assertThat(events.map { it["expiresAt"].asText() }.distinct()).hasSize(1)
        assertThat(events.map { it["actorId"].asText() })
            .containsExactly("durability-maker", "durability-checker", "durability-maker")
        val contract = mapper.valueToTree<JsonNode>(
            Yaml().load<Any>(Path.of(System.getProperty("openbank.test.settlement-contract")).readText()),
        ).at("/components/messages/OperatorApprovalChanged/payload")
        events.forEach {
            assertContract(it, contract)
            assertThat(it["aggregateId"].asText()).isEqualTo(approval.id)
            assertThat(it["action"].asText()).isEqualTo(approval.action)
            assertThat(it["resourceId"].asText()).isEqualTo(approval.resourceId)
            assertThat(java.time.OffsetDateTime.parse(it["occurredAt"].asText()))
                .isBetween(before, java.time.OffsetDateTime.now())
        }
        assertThat(events.last()["claimedAt"].isTextual).isTrue()
        assertThat(onContext { store.findPending(200) }.map { it.id }).doesNotContain(approval.id)
    }

    @Test
    fun `expiry prevents authorization but retains the approval and its evidence`() {
        val approval = create()
        execute(
            "UPDATE settlement_operator_approvals SET created_at = now() - interval '2 days', " +
                "expires_at = now() - interval '1 day' WHERE id = '${approval.id}'",
        )
        assertThat(onContext { store.find(approval.id) }).isNull()
        assertThat(onContext { store.findPending(200) }.map { it.id }).doesNotContain(approval.id)
        decide(approval.id, 404)
        assertThat(onContext { store.markExecuted(approval.id) }).isNull()
        assertThat(status(approval.id)).isEqualTo("PENDING")
        assertThat(events(approval.id)).hasSize(1)
    }

    @Test
    fun `an approved authorization cannot be claimed after expiry`() {
        val approval = create()
        decide(approval.id, 200)
        execute(
            "UPDATE settlement_operator_approvals SET created_at = now() - interval '2 days', " +
                "expires_at = now() - interval '1 day' WHERE id = '${approval.id}'",
        )
        assertThat(onContext { store.find(approval.id) }).isNull()
        assertThat(onContext { store.markExecuted(approval.id) }).isNull()
        assertThat(status(approval.id)).isEqualTo("APPROVED")
        assertThat(events(approval.id).map { it["status"].asText() }).containsExactly("PENDING", "APPROVED")
    }

    @Test
    fun `audit insertion failure rolls back approval creation`() {
        val maker = "test-create-${UUID.randomUUID()}"
        val constraint = constraintName()
        execute(
            "ALTER TABLE settlement_outbox ADD CONSTRAINT $constraint CHECK " +
                "(event_type <> 'SETTLEMENT_OPERATOR_APPROVAL_CHANGED' OR payload::jsonb->>'makerId' <> '$maker')",
        )
        try {
            assertThatThrownBy {
                onContext { store.create("settlement.create", instruction.approvalFingerprint, maker, 3600) }
            }
                .hasStackTraceContaining(constraint)
            assertThat(count("SELECT count(*) FROM settlement_operator_approvals WHERE maker_id = '$maker'")).isZero()
        } finally {
            execute("ALTER TABLE settlement_outbox DROP CONSTRAINT $constraint")
        }
    }

    @Test
    fun `audit insertion failure rolls back the HTTP checker decision and the claim`() {
        val approval = create()
        rejectAuditStatus(approval.id, "APPROVED") {
            decide(approval.id, 409)
            assertThat(status(approval.id)).isEqualTo("PENDING")
            assertThat(events(approval.id)).hasSize(1)
        }
        decide(approval.id, 200)
        rejectAuditStatus(approval.id, "EXECUTED") { constraint ->
            assertThatThrownBy { onContext { store.markExecuted(approval.id) } }
                .hasStackTraceContaining(constraint)
            assertThat(status(approval.id)).isEqualTo("APPROVED")
            assertThat(events(approval.id)).hasSize(2)
        }
        assertThat(onContext { store.markExecuted(approval.id) }?.status).isEqualTo(ApprovalStatus.EXECUTED)
        assertThat(events(approval.id)).hasSize(3)
    }

    @Test
    fun `only one concurrent checker and one concurrent claimant can succeed`(): Unit = runBlocking {
        val approval = create()
        val decisions = (1..8).map { index ->
            async(Dispatchers.IO) { runCatching { onContext { store.decide(approval.id, "checker-$index", true) } } }
        }.awaitAll()
        assertThat(decisions.count { it.isSuccess && it.getOrNull() != null }).isEqualTo(1)
        assertThat(decisions.filter { it.isFailure }.map { it.exceptionOrNull() })
            .allMatch { it is InvalidApprovalStateException }
        val claims = (1..8).map {
            async(Dispatchers.IO) { runCatching { onContext { store.markExecuted(approval.id) } } }
        }.awaitAll()
        assertThat(claims.count { it.isSuccess && it.getOrNull() != null }).isEqualTo(1)
        assertThat(claims.filter { it.isFailure }.map { it.exceptionOrNull() })
            .allMatch { it is InvalidApprovalStateException }
        assertThat(events(approval.id)).hasSize(3)
    }

    @Test
    @TestSecurity(user = "durability-maker", roles = ["ROLE_OPERATOR"])
    fun `self approval is refused without writing a checker event`() {
        val approval = create()
        decide(approval.id, 403)
        assertThat(status(approval.id)).isEqualTo("PENDING")
        assertThat(events(approval.id)).hasSize(1)
    }

    @Test
    fun `rejected approvals cannot be changed or claimed`() {
        val approval = create()
        given().contentType("application/json").body(mapOf("approve" to false))
            .patch("/api/v1/settlements/approvals/${approval.id}").then().statusCode(200)
        decide(approval.id, 409)
        assertThatThrownBy { onContext { store.markExecuted(approval.id) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
        assertThat(events(approval.id).map { it["status"].asText() }).containsExactly("PENDING", "REJECTED")
    }

    private fun assertContract(event: JsonNode, schema: JsonNode) {
        schema["required"].forEach { key ->
            assertThat(event.hasNonNull(key.asText())).describedAs("required event field %s", key.asText()).isTrue()
        }
        schema["properties"].fields().forEach { (key, shape) ->
            val value = event[key] ?: return@forEach
            if (shape.has("const")) assertThat(value).isEqualTo(shape["const"])
            if (shape.has("enum")) assertThat(shape["enum"].toList()).contains(value)
            val allowedTypes = shape["type"].let {
                if (it.isArray) it.map { type -> type.asText() } else listOf(it.asText())
            }
            val actualType = when {
                value.isNull -> "null"
                value.isIntegralNumber -> "integer"
                value.isTextual -> "string"
                else -> "unexpected"
            }
            assertThat(allowedTypes).describedAs("event field %s type", key).contains(actualType)
            if (!value.isNull && shape["format"]?.asText() == "date-time") {
                java.time.OffsetDateTime.parse(value.asText())
            }
        }
    }

    private fun create(): PendingApproval = onContext {
        store.create("settlement.create", instruction.approvalFingerprint, "durability-maker", 3600)
    }

    private fun decide(id: String, expected: Int) {
        given().contentType("application/json").body(mapOf("approve" to true, "instruction" to instruction))
            .patch("/api/v1/settlements/approvals/$id").then().statusCode(expected)
    }

    private fun rejectAuditStatus(id: String, status: String, block: (String) -> Unit) {
        val constraint = constraintName()
        execute(
            "ALTER TABLE settlement_outbox ADD CONSTRAINT $constraint CHECK " +
                "(aggregate_id <> '$id'::uuid OR payload::jsonb->>'status' <> '$status')",
        )
        try {
            block(constraint)
        } finally {
            execute("ALTER TABLE settlement_outbox DROP CONSTRAINT $constraint")
        }
    }

    private fun constraintName() = "reject_approval_${UUID.randomUUID().toString().replace("-", "")}"

    private fun status(id: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status FROM settlement_operator_approvals WHERE id = ?").use { query ->
            query.setObject(1, UUID.fromString(id))
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun events(id: String) = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT payload FROM settlement_outbox WHERE aggregate_id = ? ORDER BY id",
        ).use { query ->
            query.setObject(1, UUID.fromString(id))
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(mapper.readTree(rows.getString(1))) }
            }
        }
    }

    private fun count(sql: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { block() }
    }
}
