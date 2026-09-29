// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.integration

import com.openbank.dispute.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(ComplaintRevisionIT.AuthzOffProfile::class)
class ComplaintRevisionIT {
    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `aggregate and outbox advance together with a strict revision`() {
        val id = UUID.fromString(
            given()
                .contentType("application/json")
                .body(
                    """{"category":"PAYMENT_SERVICE","channel":"APP","description":"duplicate debit", """ +
                        """"accountId":null,"transactionId":null,"disputeId":null}""",
                )
                .post("/api/v1/complaints")
                .then().statusCode(201)
                .extract().jsonPath().getString("id"),
        )

        given()
            .contentType("application/json")
            .body("""{"reason":"awaiting scheme evidence"}""")
            .post("/api/v1/complaints/$id/interim-reply")
            .then().statusCode(200)
            .body("aggregateRevision", org.hamcrest.Matchers.equalTo(2))

        connection().use { connection ->
            connection.prepareStatement("SELECT aggregate_revision FROM complaints WHERE id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isEqualTo(2L)
                }
            }
            connection.prepareStatement(
                "SELECT payload FROM dispute_outbox WHERE aggregate_id = ? ORDER BY created_at",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    val revisions = buildList {
                        while (rows.next()) {
                            val match = Regex("\\\"aggregateRevision\\\":(\\d+)").find(rows.getString(1))
                            add(checkNotNull(match).groupValues[1].toLong())
                        }
                    }
                    assertThat(revisions).containsExactly(1L, 2L)
                }
            }
        }
    }

    private fun connection() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )
}
