// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0219 D3 suppressions, driven over real HTTP against a real Postgres.
 *
 * WHY THIS EXISTS: `SuppressionEntity` mapped six of its ten columns to names no migration
 * creates. consent-service configures no `physical-naming-strategy`, so Hibernate's implicit
 * column name is the property name verbatim and Postgres folds it to lower case — `createdAt`
 * asked for `createdat` while V6 created `created_at`. Every call to
 * `GET /api/v1/suppressions/party/{partyId}` answered 500 with
 * `SQLGrammarException: column se1_0.createdat does not exist (42703)`, from the day the endpoint
 * shipped, and `POST` failed the same way one layer down.
 *
 * Nothing in the suite could see it. The unit tests mock `SuppressionRepository`, so no SQL is
 * ever issued; the pod is Ready because health probes do not touch the table; and the sibling
 * entities in the same package spell every column out, so the file next door looked like the
 * convention was being followed. It took schemathesis fuzzing the running service to find it.
 *
 * So this test asserts the one thing a mock cannot: that the SQL Hibernate generates matches the
 * schema Flyway built. It is deliberately a round trip through the endpoints plus a plain JDBC
 * read — the JDBC assertion is what pins the physical column names, and it would go red again the
 * moment a property loses its `@Column(name = ...)`.
 *
 * Reactive-Panache repositories cannot be called from a bare @QuarkusTest thread ("No current
 * Vertx context found"); only a real HTTP request carries one. Same shape as
 * ConsentRevocationOutboxIT and LendingOutboxWriteIT.
 */
@QuarkusTest
@QuarkusTestResource(SuppressionPersistenceIT.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class SuppressionPersistenceIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("consent-events-out").toMutableMap()
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "it-operator", roles = ["ROLE_OPERATOR"])
    fun `listing suppressions for a party with none returns an empty list, not a 500`() {
        // The exact request schemathesis failed on. A party with no rows still forces Hibernate to
        // build and execute the SELECT, which is where the column names are resolved — so this
        // case, with no fixture at all, is the one that catches a broken mapping.
        When {
            get("/api/v1/suppressions/party/${UUID.randomUUID()}")
        } Then {
            statusCode(200)
            body("size()", org.hamcrest.Matchers.equalTo(0))
        }
    }

    @Test
    @TestSecurity(user = "it-operator", roles = ["ROLE_OPERATOR"])
    fun `a created suppression round-trips through the physical columns V6 created`() {
        val partyId = UUID.randomUUID()
        val id = Given {
            contentType("application/json")
            body(
                """
                {"partyId":"$partyId","scope":"ALL","value":null,
                 "reason":"CUSTOMER_OPTOUT","source":"suppression-persistence-it"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/suppressions")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(jsonPath().getString("id"))
        }

        // Read the row by its SNAKE_CASE column names. This is the assertion that pins the
        // mapping: it cannot pass against an entity that writes `createdat`, and it does not
        // depend on the entity class to name the columns for it.
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                SELECT party_id, scope, reason_code, source, created_by, created_at,
                       revoked_at, revoked_by
                  FROM suppressions
                 WHERE id = ?
                """.trimIndent(),
            ).use { st ->
                st.setObject(1, id)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).describedAs("suppression row committed").isTrue()
                    assertThat(rs.getObject("party_id")).isEqualTo(partyId)
                    assertThat(rs.getString("scope")).isEqualTo("ALL")
                    assertThat(rs.getString("reason_code")).isEqualTo("CUSTOMER_OPTOUT")
                    assertThat(rs.getString("source")).isEqualTo("suppression-persistence-it")
                    assertThat(rs.getString("created_by")).isNotBlank()
                    assertThat(rs.getTimestamp("created_at")).isNotNull()
                    assertThat(rs.getTimestamp("revoked_at")).isNull()
                    assertThat(rs.getString("revoked_by")).isNull()
                }
            }
        }

        // And the read path returns it — the half that was 500 in production.
        When {
            get("/api/v1/suppressions/party/$partyId")
        } Then {
            statusCode(200)
            body("size()", org.hamcrest.Matchers.equalTo(1))
            body("[0].id", org.hamcrest.Matchers.equalTo(id.toString()))
            body("[0].scope", org.hamcrest.Matchers.equalTo("ALL"))
            body("[0].reason", org.hamcrest.Matchers.equalTo("CUSTOMER_OPTOUT"))
        }
    }

    @Test
    @TestSecurity(user = "it-operator", roles = ["ROLE_OPERATOR"])
    fun `revoking a suppression drops it out of the active list`() {
        val partyId = UUID.randomUUID()
        val id = Given {
            contentType("application/json")
            body(
                """
                {"partyId":"$partyId","scope":"TOPIC","value":"loans",
                 "reason":"COMPLAINT","source":"suppression-persistence-it"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/suppressions")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }

        When {
            post("/api/v1/suppressions/$id/revoke")
        } Then {
            statusCode(200)
        }

        // `revoked_at IS NULL` is the active predicate the contact-policy gate reads, and it is one
        // of the columns that was mismapped — so asserting the row is GONE from the active list
        // exercises the filter against the physical column, not just the entity's idea of it.
        When {
            get("/api/v1/suppressions/party/$partyId")
        } Then {
            statusCode(200)
            body("size()", org.hamcrest.Matchers.equalTo(0))
        }

        dataSource.connection.use { c ->
            c.prepareStatement("SELECT revoked_at, revoked_by FROM suppressions WHERE id = ?").use { st ->
                st.setObject(1, UUID.fromString(id))
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getTimestamp("revoked_at")).isNotNull()
                    assertThat(rs.getString("revoked_by")).isNotBlank()
                }
            }
        }
    }
}
