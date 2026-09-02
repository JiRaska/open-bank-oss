// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LifecycleApprovalEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.delegation.lifecycle-approvals.mutations-enabled" to "true",
    )
}

/**
 * Real-Postgres proof for the current-main evidence boundary.
 *
 * Request-key replay and terminal rejection are serialised in Postgres across callers. Approval
 * remains fail-closed even when the dark mutation edge is deliberately enabled. The stronger race
 * between approval and a direct lifecycle transition belongs on the V8-V10 stacked tree, where
 * both contenders can use the same authoritative revision/CAS seam.
 */
@QuarkusTest
@TestProfile(LifecycleApprovalEnabledProfile::class)
@TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
@QuarkusTestResource(DelegationLifecycleApprovalConcurrencyIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class DelegationLifecycleApprovalConcurrencyIT {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("delegation-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `concurrent proposal retries create one durable row`() {
        val grantId = seedGrant()
        val requestKey = "it-${UUID.randomUUID()}"

        val statuses = race(
            { propose(grantId, requestKey) },
            { propose(grantId, requestKey) },
        )

        assertThat(statuses).containsExactlyInAnyOrder(HTTP_CREATED, HTTP_CREATED)
        assertThat(countByRequestKey(requestKey)).isEqualTo(1)
    }

    @Test
    fun `identical concurrent rejections keep one immutable terminal decision`() {
        val seeded = seedApproval("maker")

        val statuses = race(
            { decide(seeded.approvalId, approve = false, reason = "evidence insufficient") },
            { decide(seeded.approvalId, approve = false, reason = "evidence insufficient") },
        )

        assertThat(statuses).containsExactlyInAnyOrder(HTTP_OK, HTTP_OK)
        assertThat(text("select state from delegation_lifecycle_approvals where id = ?", seeded.approvalId))
            .isEqualTo("REJECTED")
        assertThat(text("select status from delegation_grants where id = ?", seeded.grantId))
            .isEqualTo("ACTIVE")
        assertThat(scalar("select count(*) from delegation_outbox where aggregate_id = ?", seeded.grantId))
            .isZero()
    }

    @Test
    fun `approval is fail closed even when the dark mutation edge is enabled`() {
        val seeded = seedApproval("maker")

        assertThat(decide(seeded.approvalId, approve = true, reason = "evidence checked"))
            .isEqualTo(HTTP_CONFLICT)
        assertThat(text("select state from delegation_lifecycle_approvals where id = ?", seeded.approvalId))
            .isEqualTo("PROPOSED")
        assertThat(text("select status from delegation_grants where id = ?", seeded.grantId))
            .isEqualTo("ACTIVE")
        assertThat(scalar("select count(*) from delegation_outbox where aggregate_id = ?", seeded.grantId))
            .isZero()
    }

    @Test
    fun `maker cannot reject their own proposal`() {
        val seeded = seedApproval("checker")

        assertThat(decide(seeded.approvalId, approve = false, reason = "self decision"))
            .isEqualTo(HTTP_FORBIDDEN)
        assertThat(text("select state from delegation_lifecycle_approvals where id = ?", seeded.approvalId))
            .isEqualTo("PROPOSED")
    }

    private fun seedApproval(maker: String): Seeded {
        val grantId = seedGrant()
        val approvalId = UUID.randomUUID()
        jdbc().use { connection ->
            connection.prepareStatement(
                """
                insert into delegation_lifecycle_approvals
                    (id, delegation_id, operation, requested_reason, request_key,
                     proposed_by, proposed_at, state)
                values (?, ?, 'SUSPEND', 'fraud signal reviewed', ?, ?, now(), 'PROPOSED')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, approvalId)
                statement.setObject(2, grantId)
                statement.setString(3, "it-$approvalId")
                statement.setString(4, maker)
                statement.executeUpdate()
            }
        }
        return Seeded(grantId, approvalId)
    }

    private fun seedGrant(): UUID {
        val grantId = UUID.randomUUID()
        jdbc().use { connection ->
            connection.prepareStatement(
                """
                insert into delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id,
                     approval_policy, valid_from, status, created_at, updated_at)
                values (?, ?, ?, 'ACCOUNT', ?, 'SOLO', now() - interval '1 day',
                        'ACTIVE', now() - interval '1 day', now() - interval '1 day')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, grantId)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "insert into delegation_capabilities (grant_id, capability) " +
                    "values (?, 'ACCOUNT_READ_BALANCES')",
            ).use { statement ->
                statement.setObject(1, grantId)
                statement.executeUpdate()
            }
        }
        return grantId
    }

    private fun propose(grantId: UUID, requestKey: String): Int = RestAssured.given()
        .contentType(ContentType.JSON)
        .header("X-Request-ID", requestKey)
        .body(
            """{"delegationId":"$grantId","operation":"SUSPEND","reason":"fraud signal reviewed"}""",
        )
        .post("/api/v1/delegations/approvals")
        .statusCode

    private fun decide(approvalId: UUID, approve: Boolean, reason: String): Int = RestAssured.given()
        .contentType(ContentType.JSON)
        .body("""{"approve":$approve,"reason":"$reason"}""")
        .post("/api/v1/delegations/approvals/$approvalId/decision")
        .statusCode

    private fun race(first: () -> Int, second: () -> Int): List<Int> {
        val barrier = CyclicBarrier(CONCURRENT_CALLERS)
        val pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS)
        return try {
            listOf(first, second).map { call ->
                pool.submit<Int> {
                    barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    call()
                }
            }.map { it.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun countByRequestKey(requestKey: String): Int = jdbc().use { connection ->
        connection.prepareStatement(
            "select count(*) from delegation_lifecycle_approvals where request_key = ?",
        ).use { statement ->
            statement.setString(1, requestKey)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun scalar(sql: String, id: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun text(sql: String, id: UUID): String = jdbc().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                result.next()
                result.getString(1)
            }
        }
    }

    private fun jdbc(): Connection {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        return DriverManager.getConnection(url, "openbank", "openbank_secret")
    }

    private data class Seeded(val grantId: UUID, val approvalId: UUID)

    private companion object {
        const val CONCURRENT_CALLERS = 2
        const val BARRIER_TIMEOUT_SECONDS = 10L
        const val CALL_TIMEOUT_SECONDS = 60L
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_FORBIDDEN = 403
        const val HTTP_CONFLICT = 409
    }
}
