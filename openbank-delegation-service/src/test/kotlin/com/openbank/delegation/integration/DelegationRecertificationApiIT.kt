// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.domain.model.DelegationRecertificationAudience
import com.openbank.delegation.domain.model.DelegationRecertificationCycle
import com.openbank.delegation.infrastructure.rest.dto.DelegationRecertificationResponse
import com.openbank.delegation.it.PostgresTestResource
import com.openbank.libs.idempotency.IdempotencyStore
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID

/** Real HTTP, Redis and PostgreSQL proof of customer scope, replay and pending task visibility. */
@QuarkusTest
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class DelegationRecertificationApiIT {
    @Inject
    lateinit var idempotencyStore: IdempotencyStore

    @Inject
    lateinit var mapper: ObjectMapper

    private val grantor = UUID.randomUUID()
    private val key = UUID.randomUUID().toString()

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_API"])
    fun `customer confirmation and replay record exactly one review`() {
        val cycle = seedCycle()
        confirm(cycle, grantor).statusCode(HTTP_OK)
        confirm(cycle, grantor).statusCode(HTTP_OK)
        assertThat(cycleState(cycle)).containsExactly("CONFIRMED", grantor.toString())
        assertThat(eventCount(cycle)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_API"])
    fun `missing and mismatched customer scope are rejected before confirmation or cache replay`() {
        val cycle = seedCycle()
        confirm(cycle, null).statusCode(HTTP_FORBIDDEN)
        confirm(cycle, UUID.randomUUID()).statusCode(HTTP_FORBIDDEN)
        assertThat(cycleState(cycle)).containsExactly("PENDING", null)
        assertThat(eventCount(cycle)).isZero()

        confirm(cycle, grantor).statusCode(HTTP_OK)
        confirm(cycle, null).statusCode(HTTP_FORBIDDEN)
        confirm(cycle, UUID.randomUUID()).statusCode(HTTP_FORBIDDEN)
        assertThat(eventCount(cycle)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "test-bank-operator", roles = ["ROLE_OPERATOR"])
    fun `staff cannot record a customer review by supplying a customer header`() {
        val cycle = seedCycle()
        assertStaffRejected(cycle)
        warmCache(cycle)
        assertStaffRejected(cycle)
    }

    @Test
    @TestSecurity(user = "test-bank-admin", roles = ["ROLE_ADMIN"])
    fun `administrator cannot replay a customer review by supplying a customer header`() {
        val cycle = seedCycle()
        warmCache(cycle)
        assertStaffRejected(cycle)
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_API"])
    fun `pending inbox excludes obsolete cycles without deleting their evidence`() {
        val current = seedCycle()
        val revoked = seedCycle(status = "REVOKED")
        val suspended = seedCycle(status = "SUSPENDED")
        val superseded = seedCycle(revision = 1)
        val ids = RestAssured.given().header(CUSTOMER_HEADER, grantor.toString())
            .get("/api/v1/delegations/recertifications/grantor/$grantor")
            .then().statusCode(HTTP_OK).extract().jsonPath().getList<String>("id")
        assertThat(ids).containsExactly(current.id.toString())
        listOf(current, revoked, suspended, superseded).forEach {
            assertThat(cycleState(it)).containsExactly("PENDING", null)
        }
    }

    private fun assertStaffRejected(cycle: DelegationRecertificationCycle) {
        confirm(cycle, grantor).statusCode(HTTP_FORBIDDEN)
        confirm(cycle, null).statusCode(HTTP_FORBIDDEN)
        assertThat(cycleState(cycle)).containsExactly("PENDING", null)
        assertThat(eventCount(cycle)).isZero()
    }

    private fun confirm(cycle: DelegationRecertificationCycle, customer: UUID?): ValidatableResponse {
        val request = RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", key)
            .queryParam("grantorPartyId", grantor.toString())
        if (customer != null) request.header(CUSTOMER_HEADER, customer.toString())
        return request.post("/api/v1/delegations/recertifications/${cycle.id}/confirm").then()
    }

    private fun warmCache(cycle: DelegationRecertificationCycle): Unit = runBlocking {
        val response = DelegationRecertificationResponse.from(cycle.confirm(grantor, OffsetDateTime.now()))
        idempotencyStore.save(
            "delegation:recertification-confirm:${cycle.id}:$grantor:$key",
            HTTP_OK,
            mapper.writeValueAsString(response),
        )
    }

    private fun seedCycle(status: String = "ACTIVE", revision: Long = 0): DelegationRecertificationCycle {
        val cycle = DelegationRecertificationCycle(
            delegationId = UUID.randomUUID(),
            grantorPartyId = grantor,
            expectedLifecycleRevision = 0,
            audience = DelegationRecertificationAudience.CORPORATE,
            sequence = 1,
            dueAt = OffsetDateTime.now().minusDays(1),
            createdAt = OffsetDateTime.now().minusDays(1),
        )
        jdbc().use { connection ->
            seedGrant(connection, cycle, status, revision)
            connection.prepareStatement(
                """
                insert into delegation_recertification_cycles
                    (id, delegation_id, grantor_party_id, expected_lifecycle_revision,
                     audience, sequence, due_at, status, created_at)
                values (?, ?, ?, 0, 'CORPORATE', 1, ?, 'PENDING', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, cycle.id)
                statement.setObject(2, cycle.delegationId)
                statement.setObject(3, grantor)
                statement.setObject(4, cycle.dueAt)
                statement.setObject(5, cycle.createdAt)
                statement.executeUpdate()
            }
        }
        return cycle
    }

    private fun seedGrant(
        connection: Connection,
        cycle: DelegationRecertificationCycle,
        status: String,
        revision: Long,
    ) {
        connection.prepareStatement(
            """
            insert into delegation_grants
                (id, grantor_party_id, grantee_party_id, resource_type, resource_id, approval_policy,
                 valid_from, status, created_at, updated_at, lifecycle_revision, recertification_audience)
            values (?, ?, ?, 'ACCOUNT', ?, 'SOLO', now() - interval '1 day', ?, now(), now(), ?, 'CORPORATE')
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, cycle.delegationId)
            statement.setObject(2, grantor)
            statement.setObject(3, UUID.randomUUID())
            statement.setObject(4, UUID.randomUUID())
            statement.setString(5, status)
            statement.setLong(6, revision)
            statement.executeUpdate()
        }
    }

    private fun cycleState(cycle: DelegationRecertificationCycle): List<String?> = jdbc().use { connection ->
        connection.prepareStatement(
            "select status, confirmed_by from delegation_recertification_cycles where id = ?",
        ).use { statement ->
            statement.setObject(1, cycle.id)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "cycle evidence must remain present" }
                listOf(rows.getString(1), rows.getString(2))
            }
        }
    }

    private fun eventCount(cycle: DelegationRecertificationCycle): Int = jdbc().use { connection ->
        connection.prepareStatement(
            "select count(*) from delegation_outbox where aggregate_id = ? and event_type = 'DelegationRecertificationConfirmed'",
        ).use { statement ->
            statement.setObject(1, cycle.delegationId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private fun jdbc(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        const val EDGE = "service-account-openbank-edge"
        const val CUSTOMER_HEADER = "X-Customer-Party-Id"
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403
    }
}
