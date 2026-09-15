// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private const val REVOCATION_OWNER = "536a4369-2604-439e-bab1-3c681e5a513d"

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(OutboxDispatchDisabledProfile::class)
@TestSecurity(user = "test-revocation-operator", roles = ["ROLE_OPERATOR"])
class ScaDeviceRevocationIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    fun `revoked credential cannot decide or be reactivated by enrollment`() {
        val fixture = fixture()
        revoke(fixture, 204)
        revoke(fixture, 204)
        decide(fixture, 403)
        given().contentType("application/json").body(
            mapOf(
                "credentialId" to fixture.credential,
                "publicKey" to Base64.getEncoder().encodeToString(fixture.keys.public.encoded),
                "algorithm" to "ES256",
            ),
        ).post("/api/v1/sca/parties/${fixture.party}/devices").then().statusCode(409)
        assertThat(count("sca_outbox", "aggregate_id = '${fixture.device}' AND event_type = 'DEVICE_REVOKED'"))
            .isEqualTo(1)
    }

    @Test
    fun `revocation cancels an already completed unconsumed approval and retains evidence`() {
        val fixture = fixture()
        decide(fixture, 200)
        given().contentType("application/json").body(mapOf("partyId" to fixture.party))
            .post("/api/v1/sca/challenges/${fixture.challenge}/verify").then().statusCode(200)
            .body("status", equalTo("COMPLETED"))
        revoke(fixture, 204)
        given().get("/api/v1/sca/challenges/${fixture.challenge}").then().statusCode(200)
            .body("status", equalTo("CANCELLED"))
        given().contentType("application/json").body(mapOf("partyId" to fixture.party))
            .post("/api/v1/sca/challenges/${fixture.challenge}/consume").then().statusCode(400)
        assertThat(count("sca_device_decisions", "challenge_id = '${fixture.challenge}'")).isEqualTo(1)
        assertThat(
            count(
                "sca_enrolled_devices d JOIN sca_outbox o ON o.aggregate_id = d.id " +
                    "JOIN sca_challenges c ON c.id = '${fixture.challenge}'",
                "d.id = '${fixture.device}' AND o.event_type = 'DEVICE_REVOKED' " +
                    "AND d.revoked_at IS NOT NULL AND d.xmin = o.xmin AND c.xmin = d.xmin",
            ),
        ).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = REVOCATION_OWNER, roles = ["ROLE_CUSTOMER"])
    fun `a customer can revoke their own device but not a different party device`() {
        val own = fixture(UUID.fromString(REVOCATION_OWNER))
        val foreign = fixture()
        revoke(foreign, 403)
        revoke(own, 204)
        assertThat(count("sca_outbox", "aggregate_id = '${foreign.device}' AND event_type = 'DEVICE_REVOKED'"))
            .isZero()
    }

    @Test
    @TestSecurity(user = "unresolved-customer", roles = ["ROLE_CUSTOMER", "ROLE_API"])
    fun `an unresolved customer cannot borrow the API role to revoke a device`() {
        revoke(fixture(), 403)
    }

    @Test
    fun `audit failure rolls back credential revocation and challenge cancellation`() {
        val fixture = fixture()
        decide(fixture, 200)
        val constraint = "reject_revoke_${fixture.device.toString().replace("-", "")}"
        execute(
            "ALTER TABLE sca_outbox ADD CONSTRAINT $constraint " +
                "CHECK (event_type <> 'DEVICE_REVOKED' OR aggregate_id <> '${fixture.device}'::uuid)",
        )
        try {
            revoke(fixture, 409)
            assertThat(count("sca_enrolled_devices", "id = '${fixture.device}' AND revoked_at IS NULL")).isEqualTo(1)
            given().contentType("application/json").body(mapOf("partyId" to fixture.party))
                .post("/api/v1/sca/challenges/${fixture.challenge}/verify").then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
        } finally {
            execute("ALTER TABLE sca_outbox DROP CONSTRAINT $constraint")
        }
        revoke(fixture, 204)
    }

    @Test
    fun `a concurrent decision cannot survive credential revocation as a usable approval`() {
        val fixture = fixture()
        // Dedicated threads inherit Quarkus' test classloader; the common pool does not.
        Executors.newFixedThreadPool(2).use { executor ->
            val start = CountDownLatch(1)
            val decision = CompletableFuture.supplyAsync({
                start.await()
                decide(fixture)
            }, executor)
            val revocation = CompletableFuture.supplyAsync({
                start.await()
                given().delete("/api/v1/sca/parties/${fixture.party}/devices/${fixture.device}").statusCode
            }, executor)
            start.countDown()
            assertThat(revocation.get(30, TimeUnit.SECONDS)).isEqualTo(204)
            assertThat(decision.get(30, TimeUnit.SECONDS)).isIn(200, 403, 409)
        }
        assertThat(
            count(
                "sca_device_decisions d JOIN sca_challenges c ON c.id = d.challenge_id",
                "c.id = '${fixture.challenge}' AND c.consumed_at IS NULL AND c.status IN ('PENDING', 'COMPLETED')",
            ),
        ).isZero()
        decide(fixture, 403)
    }

    private fun fixture(party: UUID = UUID.randomUUID()): RevocationFixture {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val fixture = RevocationFixture(party, UUID.randomUUID(), UUID.randomUUID(), generator.generateKeyPair())
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO sca_enrolled_devices " +
                    "(id, party_id, credential_id, public_key_spki, algorithm, created_at) " +
                    "VALUES (?, ?, ?, ?, 'ES256', now())",
            ).use { query ->
                query.setObject(1, fixture.device)
                query.setObject(2, party)
                query.setString(3, fixture.credential)
                query.setString(4, Base64.getEncoder().encodeToString(fixture.keys.public.encoded))
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
            connection.prepareStatement(
                "INSERT INTO sca_challenges " +
                    "(id, party_id, purpose, method, status, expires_at, created_at, attempt_count, max_attempts) " +
                    "VALUES (?, ?, 'LOGIN', 'PUSH_NOTIFICATION', 'PENDING', now() + interval '5 minutes', now(), 0, 3)",
            ).use { query ->
                query.setObject(1, fixture.challenge)
                query.setObject(2, party)
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
        }
        return fixture
    }

    private fun revoke(fixture: RevocationFixture, expected: Int) {
        given().delete("/api/v1/sca/parties/${fixture.party}/devices/${fixture.device}").then().statusCode(expected)
    }

    private fun decide(fixture: RevocationFixture, expected: Int? = null): Int {
        val payload = listOf(fixture.challenge.toString(), "APPROVED", "", "", "", "").joinToString("|")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(fixture.keys.private)
            update(payload.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val status = given().contentType("application/json").body(
            mapOf("credentialId" to fixture.credential, "decision" to "APPROVED", "signature" to signature),
        ).post("/api/v1/sca/challenges/${fixture.challenge}/decision").statusCode
        if (expected != null) assertThat(status).isEqualTo(expected)
        return status
    }

    private fun count(table: String, predicate: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { query ->
            query.executeQuery("SELECT count(*) FROM $table WHERE $predicate").use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }
}

private data class RevocationFixture(val party: UUID, val device: UUID, val challenge: UUID, val keys: KeyPair) {
    val credential: String = "revoke-$device"
}
