// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.rest

import com.openbank.casecoordinator.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class KillSwitchStatusResourceIT {

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun clean() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM case_kill_switch").executeUpdate()
        }
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `kill-switch status is inactive when no row exists`() {
        val response = given()
            .`when`().get("/api/v1/case-coordinator/kill-switch")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("active", equalTo(false))
            .extract()
            .jsonPath()

        assertThat(response.getList<Any>("scopes")).isEmpty()
    }

    @Test
    @TestSecurity(user = "admin-1", roles = ["ROLE_ADMIN"])
    fun `kill-switch status reflects active scopes`() {
        seed(scope = "rca-investigator", reason = "incident containment", setBy = "admin-1")

        given()
            .`when`().get("/api/v1/case-coordinator/kill-switch")
            .then()
            .statusCode(200)
            .body("active", equalTo(true))
            .body("scopes.size()", equalTo(1))
            .body("scopes[0].scope", equalTo("rca-investigator"))
            .body("scopes[0].reason", equalTo("incident containment"))
            .body("scopes[0].setBy", equalTo("admin-1"))
    }

    private fun seed(scope: String, reason: String, setBy: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO case_kill_switch (scope, reason, set_by, source_event_id, set_at, removed_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                ON CONFLICT (scope) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scope)
                statement.setString(2, reason)
                statement.setString(3, setBy)
                statement.setObject(4, UUID.nameUUIDFromBytes(scope.toByteArray(StandardCharsets.UTF_8)))
                statement.setTimestamp(5, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }
}
