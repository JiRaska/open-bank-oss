// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasEntry
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
class ContextApiIT {
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
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `incident lens exposes aggregate counts without affected identifiers`() {
        val reference = UUID.randomUUID().toString()
        val root = "incident:$reference"
        seedAssignment(CASE, PURPOSE)
        seedNode(root, "INCIDENT", "Incident $reference", namespace = "INCIDENT")
        repeat(2) {
            val payment = "payment:${UUID.randomUUID()}"
            seedNode(payment, "PAYMENT", "Payment", namespace = "INCIDENT")
            seedEdge(root, payment, "AFFECTED", namespace = "INCIDENT")
        }

        val response = given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", PURPOSE)
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
            (assignment_id, principal_id, case_id, purpose, valid_from, valid_to, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (principal_id, case_id, purpose, valid_from) DO NOTHING
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
            (node_key, namespace, node_type, source_system, source_ref, display_label,
             classification, valid_from, recorded_at, source_version)
            VALUES (?, ?, ?, 'test', ?, ?, 'INTERNAL', ?, ?, 1)
        """.trimIndent(),
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
            (edge_id, namespace, from_key, to_key, relation_type, source_system, evidence_ref,
             valid_from, recorded_at, source_version)
            VALUES (?, ?, ?, ?, ?, 'test', ?, ?, ?, 1)
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
