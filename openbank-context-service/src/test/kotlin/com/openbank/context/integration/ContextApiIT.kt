// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.MakerCheckerViolation
import com.openbank.context.infrastructure.ProposeAssignmentRequest
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasEntry
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import jakarta.enterprise.inject.Any as AnyQualifier

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextApiIT {
    @Inject
    @AnyQualifier
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var assignmentAdministration: AssignmentAdministrationService

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `maker checker assignment grants access and revocation removes it immediately`() {
        val reference = "CMP-ACCESS-${UUID.randomUUID()}"
        val caseId = "case-access-${UUID.randomUUID()}"
        seedNode("complaint:$reference", "COMPLAINT", "Complaint $reference")
        val request = ProposeAssignmentRequest(ACTOR, caseId, PURPOSE, null, Instant.now().plusSeconds(3600))
        val proposal = onVertxContext { assignmentAdministration.propose(request, "maker-1") }
        assertThat(proposal.status).isEqualTo("PENDING")

        assertThatThrownBy {
            onVertxContext { assignmentAdministration.decide(proposal.id, true, "maker-1") }
        }.isInstanceOf(MakerCheckerViolation::class.java)

        val approved = onVertxContext { assignmentAdministration.decide(proposal.id, true, "checker-2") }
        val assignmentId = requireNotNull(approved.assignmentId)
        assertThat(onVertxContext { assignmentAdministration.active(50) }.map { it.id }).contains(assignmentId)

        given().header("X-Investigation-Case-Id", caseId).header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference").then().statusCode(200)

        onVertxContext { assignmentAdministration.revoke(assignmentId, "admin-3") }
        assertThat(onVertxContext { assignmentAdministration.active(50) }.map { it.id }).doesNotContain(assignmentId)
        given().header("X-Investigation-Case-Id", caseId).header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference").then().statusCode(403)
        assertThat(count("context_assignment_change_audit", "case_id = ?", caseId)).isEqualTo(3)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `complaint event is projected idempotently and becomes authorized graph evidence`() {
        val complaintId = UUID.randomUUID()
        val reference = "CMP-E2E-${UUID.randomUUID()}"
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val disputeId = UUID.randomUUID()
        val version = NOW.epochSecond * 1_000_000_000 + NOW.nano
        val payload = complaintEvent(complaintId, reference, accountId, transactionId, disputeId, version)
        val source = connector.source<String>("dispute-events-in")
        val payments = connector.source<String>("domestic-payment-events-in")
        source.runOnVertxContext(true)
        payments.runOnVertxContext(true)

        source.send(payload)
        source.send(payload)
        source.send(complaintEvent(complaintId, reference, accountId, transactionId, disputeId, version - 1))
        source.send("""{"eventType":"dispute.opened","disputeId":"${UUID.randomUUID()}"}""")
        payments.send(domesticPaymentEvent(transactionId, 1, "RECEIVED"))
        payments.send(domesticPaymentEvent(transactionId, 2, "SENT_TO_CLEARING"))
        payments.send(domesticPaymentEvent(transactionId, 3, "RETURNED"))
        payments.send(domesticPaymentEvent(transactionId, 3, "RETURNED"))
        awaitCount("context_projection_events", "aggregate_ref", "complaint:$reference", 2)
        awaitCount("context_projection_events", "aggregate_ref", "transaction:$transactionId", 3)
        assertThat(count("context_nodes", "node_key LIKE ?", "%$complaintId%")).isZero()
        assertThat(count("context_nodes", "node_key = ?", "complaint:$reference")).isEqualTo(1)
        assertThat(count("context_edges", "from_key = ?", "complaint:$reference")).isEqualTo(3)
        assertThat(stringValue("context_nodes", "source_system", "node_key", "transaction:$transactionId"))
            .isEqualTo("domestic-payment")
        val unrelatedComplaint = "complaint:CMP-OTHER-${UUID.randomUUID()}"
        seedNode(unrelatedComplaint, "COMPLAINT", "Unrelated complaint")
        seedEdge(unrelatedComplaint, "transaction:$transactionId", "CONCERNS_TRANSACTION")
        seedEdge("transaction:$transactionId", unrelatedComplaint, "CREATED")

        seedAssignment(CASE, PURPOSE)
        val response = given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference")
            .then().statusCode(200)
            .body("nodes.size()", equalTo(7))
            .body("edges.size()", equalTo(6))
            .body("edges.relation", org.hamcrest.Matchers.hasItems("CREATED", "SUBMITTED_TO", "RETURNED_BY"))
            .extract().asString()
        assertThat(response).doesNotContain(unrelatedComplaint)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `complaint lens reads bounded evidence and records the authorized access`() {
        val reference = UUID.randomUUID().toString()
        val root = "complaint:$reference"
        val payment = "payment:${UUID.randomUUID()}"
        seedAssignment(CASE, PURPOSE)
        seedNode(root, "COMPLAINT", "Complaint $reference")
        seedNode(payment, "PAYMENT", "Payment evidence")
        seedEdge(root, payment, "TRACES")

        given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference")
            .then().statusCode(200)
            .body("root", equalTo(root))
            .body("nodes.size()", equalTo(2))
            .body("edges.size()", equalTo(1))

        assertThat(auditDecisions(root)).containsExactly("ALLOWED")
        assertThatThrownBy {
            execute("DELETE FROM context_read_audit WHERE root_ref = ?", root)
        }.hasMessageContaining("context audit records are append-only")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `ICT incident events project current aggregate service impact without customer identifiers`() {
        val incidentId = UUID.randomUUID()
        val source = connector.source<String>("ict-incident-events-in")
        source.runOnVertxContext(true)
        val version = NOW.epochSecond * 1_000_000_000 + NOW.nano
        source.send(incidentEvent(incidentId, version, listOf("ledger-service", "payment-service")))
        source.send(incidentEvent(incidentId, version + 1, listOf("ledger-service"), status = "CONTAINED"))

        awaitCount("context_projection_events", "aggregate_ref", "incident:$incidentId", 2)
        assertThat(count("context_edges", "from_key = ?", "incident:$incidentId")).isEqualTo(1)
        seedAssignment(CASE, "INCIDENT_IMPACT")

        val response = given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", "INCIDENT_IMPACT")
            .`when`().get("/api/v1/context/incidents/$incidentId/impact")
            .then().statusCode(200)
            .body("affectedByType", hasEntry("SERVICE", 1))
            .body("total", equalTo(1))
            .extract().asString()

        assertThat(response).doesNotContain("ledger-service")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `incident lens exposes aggregate counts without affected identifiers`() {
        val reference = UUID.randomUUID().toString()
        val root = "incident:$reference"
        seedAssignment(CASE, "INCIDENT_IMPACT")
        seedNode(root, "INCIDENT", "Incident $reference", namespace = "INCIDENT")
        repeat(2) {
            val payment = "payment:${UUID.randomUUID()}"
            seedNode(payment, "PAYMENT", "Payment", namespace = "INCIDENT")
            seedEdge(root, payment, "AFFECTED", namespace = "INCIDENT")
        }

        val response = given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", "INCIDENT_IMPACT")
            .`when`().get("/api/v1/context/incidents/$reference/impact")
            .then().statusCode(200)
            .body("affectedByType", hasEntry("PAYMENT", 2))
            .body("total", equalTo(2))
            .body("drilldownAvailable", equalTo(false))
            .extract().asString()

        assertThat(response).doesNotContain("payment:")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `missing assignment is denied and durably audited before graph access`() {
        val reference = UUID.randomUUID().toString()
        val root = "complaint:$reference"
        seedNode(root, "COMPLAINT", "Complaint $reference")

        given()
            .header("X-Investigation-Case-Id", "unassigned-${UUID.randomUUID()}")
            .header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference")
            .then().statusCode(403)

        assertThat(auditDecisions(root)).containsExactly("DENIED")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `an assignment for another purpose cannot be replayed against the complaint lens`() {
        val reference = UUID.randomUUID().toString()
        seedAssignment(CASE, "INCIDENT_IMPACT")
        seedNode("complaint:$reference", "COMPLAINT", "Complaint $reference")

        given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", "INCIDENT_IMPACT")
            .`when`().get("/api/v1/context/complaints/$reference")
            .then().statusCode(403)

        assertThat(auditDecisions("complaint:$reference")).containsExactly("DENIED")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `missing investigation headers are rejected as bad requests`() {
        given()
            .`when`().get("/api/v1/context/complaints/${UUID.randomUUID()}")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_VIEWER"])
    fun `an unprivileged role cannot reach the lens`() {
        given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/${UUID.randomUUID()}")
            .then().statusCode(403)
    }

    private fun seedAssignment(caseId: String, purpose: String) = execute(
        """INSERT INTO context_case_assignments
            (assignment_id, bank_scope, principal_id, case_id, purpose, valid_from, valid_to, created_at)
            VALUES (?, 'openbank-cz', ?, ?, ?, ?, ?, ?)
            ON CONFLICT (bank_scope, principal_id, case_id, purpose, valid_from) DO NOTHING
        """.trimIndent(),
        UUID.randomUUID(),
        ACTOR,
        caseId,
        purpose,
        NOW.minusSeconds(3600),
        NOW.plusSeconds(3600),
        NOW,
    )

    private fun seedNode(key: String, type: String, label: String, namespace: String = "COMPLAINT") = execute(
        """INSERT INTO context_nodes
            (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system, source_ref, display_label,
             classification, valid_from, recorded_at, source_version)
            VALUES (?, ?, 'openbank-cz', 1, ?, ?, 'test', ?, ?, 'INTERNAL', ?, ?, 1)
        """.trimIndent(),
        UUID.randomUUID(),
        key,
        namespace,
        type,
        key,
        label,
        NOW.minusSeconds(60),
        NOW,
    )

    private fun seedEdge(from: String, to: String, relation: String, namespace: String = "COMPLAINT") = execute(
        """INSERT INTO context_edges
            (edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type, source_system, evidence_ref,
             valid_from, recorded_at, source_version)
            VALUES (?, 'openbank-cz', 1, ?, ?, ?, ?, 'test', ?, ?, ?, 1)
        """.trimIndent(),
        UUID.randomUUID(), namespace, from, to, relation, "evidence:${UUID.randomUUID()}", NOW.minusSeconds(60), NOW,
    )

    private fun auditDecisions(root: String): List<String> = connection().use { connection ->
        connection.prepareStatement(
            "SELECT decision FROM context_read_audit WHERE principal_id = ? AND root_ref = ? ORDER BY occurred_at",
        ).use { statement ->
            statement.setString(1, ACTOR)
            statement.setString(2, root)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun count(table: String, predicate: String, value: Any): Int = connection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM $table WHERE $predicate").use { statement ->
            statement.setObject(1, value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun stringValue(table: String, column: String, keyColumn: String, key: Any): String =
        connection().use { connection ->
            connection.prepareStatement("SELECT $column FROM $table WHERE $keyColumn = ?").use { statement ->
                statement.setObject(1, key)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "No $table row for $keyColumn=$key" }
                    rows.getString(1)
                }
            }
        }

    private fun awaitCount(table: String, column: String, value: Any, expected: Int) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (count(table, "$column = ?", value) == expected) return
            Thread.sleep(25)
        }
        assertThat(count(table, "$column = ?", value)).isEqualTo(expected)
    }

    private fun complaintEvent(
        complaintId: UUID,
        reference: String,
        accountId: UUID,
        transactionId: UUID,
        disputeId: UUID,
        sourceVersion: Long,
    ) = """{"schemaVersion":1,"sourceVersion":$sourceVersion,"aggregateRevision":$sourceVersion,""" +
        """"eventType":"complaint.received",""" +
        """"sourceService":"dispute-service","complaintId":"$complaintId",""" +
        """"reference":"$reference","status":"RECEIVED",""" +
        """"occurredAt":"$NOW","accountId":"$accountId","transactionId":"$transactionId",""" +
        """"disputeId":"$disputeId"}"""

    private fun incidentEvent(
        incidentId: UUID,
        sourceVersion: Long,
        services: List<String>,
        status: String = "OPEN",
    ): String {
        val affectedServices = services.joinToString(",") { "\"$it\"" }
        return """{"schemaVersion":1,"sourceVersion":$sourceVersion,"aggregateRevision":$sourceVersion,""" +
            """"eventType":"ICT_INCIDENT_STATUS_CHANGED",""" +
            """"sourceService":"security-scanner","occurredAt":"$NOW","incident":{"id":"$incidentId",""" +
            """"severity":"P1_CRITICAL","status":"$status","affectedServices":[$affectedServices],""" +
            """"detectedAt":"$NOW","updatedAt":"$NOW"}}"""
    }

    private fun domesticPaymentEvent(paymentId: UUID, revision: Long, status: String): String {
        val eventType = if (revision == 1L) "DOMESTIC_PAYMENT_CREATED" else "DOMESTIC_PAYMENT_STATUS_CHANGED"
        val statusField = if (revision == 1L) "\"status\":\"$status\"" else "\"newStatus\":\"$status\""
        return """{"eventType":"$eventType","sourceService":"domestic-payment",""" +
            """"paymentId":"$paymentId","aggregateRevision":$revision,$statusField,""" +
            """"occurredAt":"${NOW.plusSeconds(revision)}"}"""
    }

    private fun execute(sql: String, vararg values: Any) = connection().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value ->
                statement.setObject(index + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
            }
            statement.executeUpdate()
        }
    }

    private fun connection() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )

    private companion object {
        const val ACTOR = "context-investigator"
        const val CASE = "case-context-it"
        const val PURPOSE = "PAYMENT_COMPLAINT"
        val NOW: Instant = Instant.now()
    }
}

class ContextMessagingTestResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = InMemoryConnector.switchIncomingChannelsToInMemory(
        "dispute-events-in",
        "ict-incident-events-in",
        "domestic-payment-events-in",
    ) + mapOf("openbank.context.require-strict-revisions" to "true")

    override fun stop() = InMemoryConnector.clear()
}
