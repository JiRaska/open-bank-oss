// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

import com.openbank.delegation.domain.model.DelegationRecertificationAudience
import com.openbank.delegation.domain.model.DelegationRecertificationCycle
import com.openbank.delegation.it.PostgresTestResource
import com.openbank.libs.idempotency.IdempotencyStore
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID

/**
 * PR #10922 binds an `Idempotency-Key`/`X-Request-ID` to a SHA-256 fingerprint of (method,
 * concrete path, canonicalised body): `DelegationResource.confirmRecertification` and
 * `DelegationPortfolioResource.create` now atomically `reserve(key, hash)` instead of a plain
 * `get(key)`, so a stored record whose fingerprint disagrees with the CURRENT request is refused
 * (409 IDEMPOTENCY_KEY_REUSED) instead of replayed. For `confirmRecertification` specifically,
 * the fingerprint has no request body (method + path + the ids already in the cache key), so a
 * mismatch can only arise from a stale/legacy record — this test simulates exactly that by
 * pre-seeding one with the wrong hash directly, rather than through two live requests with
 * different bodies (there is no body to vary).
 *
 * Both endpoints call out to a downstream port (`DelegationRecertificationUseCase`,
 * `ResourceOwnershipClient`) on a genuine cache MISS, which this suite does not stub — so both
 * tests here pre-seed the Redis-backed [IdempotencyStore] with a record carrying the WRONG
 * fingerprint for the request under test (the same technique `DelegationRecertificationApiIT`'s
 * `warmCache` already uses to seed a genuine-match replay). A mismatched fingerprint makes
 * `lookup` throw before the resource method reaches the downstream call, so the test proves the
 * guard fires over real HTTP + real Redis without needing to also stand up those dependencies.
 */
@QuarkusTest
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class DelegationIdempotencyFingerprintIT {

    @Inject
    lateinit var idempotencyStore: IdempotencyStore

    private val grantor = UUID.randomUUID()

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `confirmRecertification refuses a key whose stored fingerprint does not match`() {
        val cycle = seedCycle()
        val key = UUID.randomUUID().toString()
        val cacheKey = "delegation:recertification-confirm:${cycle.id}:$grantor:$key"
        // A record for a DIFFERENT request (wrong hash) already occupies this Idempotency-Key.
        runBlocking {
            idempotencyStore.save(cacheKey, "0".repeat(64), 200, """{"stale":"true"}""")
        }

        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .header("X-Customer-Party-Id", grantor.toString())
            .queryParam("grantorPartyId", grantor.toString())
            .post("/api/v1/delegations/recertifications/${cycle.id}/confirm")
            .then()
            .statusCode(409)
            .body("code", org.hamcrest.Matchers.equalTo("IDEMPOTENCY_KEY_REUSED"))

        // The confirm use case must never have been reached: cycle stays PENDING.
        assertThat(cycleStatus(cycle)).isEqualTo("PENDING")
    }

    @Test
    @TestSecurity(user = "test-customer", roles = ["ROLE_API"])
    fun `portfolio create refuses a key whose stored fingerprint does not match`() {
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        val cacheKey = "delegation:portfolio:create:$owner:$key"
        runBlocking {
            idempotencyStore.save(cacheKey, "1".repeat(64), 201, """{"stale":"true"}""")
        }

        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .header("X-Customer-Party-Id", owner.toString())
            .body(
                """{"ownerPartyId":"$owner","name":"Fingerprint Probe","accountIds":["${UUID.randomUUID()}"]}""",
            )
            .post("/api/v1/delegation-portfolios")
            .then()
            .statusCode(409)
            .body("code", org.hamcrest.Matchers.equalTo("IDEMPOTENCY_KEY_REUSED"))

        // The create use case (and its downstream account-ownership call) must never have been
        // reached: no portfolio row for this owner. Read over plain JDBC, not the reactive Panache
        // repository — that needs a Vert.x context a bare @QuarkusTest thread does not carry.
        assertThat(portfolioCountByOwner(owner)).isZero()
    }

    private fun seedCycle(): DelegationRecertificationCycle {
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
            connection.prepareStatement(
                """
                insert into delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, approval_policy,
                     valid_from, status, created_at, updated_at, lifecycle_revision, recertification_audience)
                values (?, ?, ?, 'ACCOUNT', ?, 'SOLO', now() - interval '1 day', 'ACTIVE', now(), now(), 0, 'CORPORATE')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, cycle.delegationId)
                statement.setObject(2, grantor)
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.executeUpdate()
            }
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

    private fun portfolioCountByOwner(owner: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement("select count(*) from delegation_portfolios where owner_party_id = ?").use {
            it.setObject(1, owner)
            val rs = it.executeQuery()
            rs.next()
            rs.getInt(1)
        }
    }

    private fun cycleStatus(cycle: DelegationRecertificationCycle): String? = jdbc().use { connection ->
        connection.prepareStatement(
            "select status from delegation_recertification_cycles where id = ?",
        ).use { statement ->
            statement.setObject(1, cycle.id)
            val rs = statement.executeQuery()
            if (rs.next()) rs.getString("status") else null
        }
    }

    private fun jdbc(): Connection = DriverManager.getConnection(
        org.eclipse.microprofile.config.ConfigProvider.getConfig().getValue(
            "quarkus.datasource.jdbc.url",
            String::class.java,
        ),
        "openbank",
        "openbank_secret",
    )
}
