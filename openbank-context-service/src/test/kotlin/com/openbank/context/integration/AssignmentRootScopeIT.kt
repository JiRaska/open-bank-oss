// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class AssignmentRootScopeIT {
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_ADMIN"])
    fun `legacy unscoped complaint grant never authorizes a root even for admin`() {
        val caseId = "legacy-${UUID.randomUUID()}"
        val reference = "CMP-${UUID.randomUUID()}"
        seedLegacyAssignment(caseId, "PAYMENT_COMPLAINT")

        given().header("X-Investigation-Case-Id", caseId)
            .header("X-Investigation-Purpose", "PAYMENT_COMPLAINT")
            .`when`().get("/api/v1/context/complaints/$reference").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_ADMIN"])
    fun `legacy unscoped incident grant never authorizes a root even for admin`() {
        val caseId = "legacy-${UUID.randomUUID()}"
        seedLegacyAssignment(caseId, "INCIDENT_IMPACT")

        given().header("X-Investigation-Case-Id", caseId)
            .header("X-Investigation-Purpose", "INCIDENT_IMPACT")
            .`when`().get("/api/v1/context/incidents/${UUID.randomUUID()}/impact").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_ADMIN"])
    fun `proposal contract requires an exact root for complaint and incident`() {
        for ((purpose, root) in listOf(
            "PAYMENT_COMPLAINT" to "complaint:CMP-42",
            "INCIDENT_IMPACT" to "incident:${UUID.randomUUID()}",
        )) {
            val body = mapOf(
                "principalId" to ACTOR,
                "caseId" to "case-${UUID.randomUUID()}",
                "purpose" to purpose,
                "validTo" to Instant.now().plusSeconds(3600).toString(),
            )
            given().contentType("application/json").body(body)
                .`when`().post("/api/v1/context/assignment-proposals").then().statusCode(400)
            given().contentType("application/json").body(body + ("rootRef" to root))
                .`when`().post("/api/v1/context/assignment-proposals").then().statusCode(201)
                .body("rootRef", equalTo(root))
        }
    }

    private fun seedLegacyAssignment(caseId: String, purpose: String) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                """INSERT INTO context_case_assignments
                    (assignment_id, bank_scope, principal_id, case_id, purpose, valid_from, valid_to, created_at)
                    VALUES (?, 'openbank-cz', ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                val now = Instant.now()
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, ACTOR)
                statement.setString(3, caseId)
                statement.setString(4, purpose)
                statement.setObject(5, OffsetDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC))
                statement.setObject(6, OffsetDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC))
                statement.setObject(7, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val ACTOR = "root-scope-admin"
    }
}
