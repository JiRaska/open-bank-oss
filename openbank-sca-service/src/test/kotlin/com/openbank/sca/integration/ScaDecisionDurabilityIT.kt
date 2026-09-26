// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.sql.ResultSet
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(OutboxDispatchDisabledProfile::class)
@TestSecurity(user = "test-decision-operator", roles = ["ROLE_OPERATOR"])
class ScaDecisionDurabilityIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var redis: ReactiveRedisDataSource

    @Inject lateinit var objectMapper: ObjectMapper

    @Test
    fun `an acknowledged signed decision survives removal of its Redis entry`() {
        val fixture = fixture()
        decide(fixture, 200)
        runBlocking { redis.key(String::class.java).del("sca:decision:${fixture.challenge}").awaitSuspending() }
        given().contentType("application/json").body(mapOf("partyId" to fixture.party))
            .post("/api/v1/sca/challenges/${fixture.challenge}/verify").then().statusCode(200)
            .body("status", equalTo("COMPLETED"))
            .body("decidedByPartyId", equalTo(fixture.party.toString()))
    }

    @Test
    fun `decision and audit event commit in the same database transaction`() {
        val fixture = fixture()
        decide(fixture, 200)
        val writers = writers(fixture.challenge)
        assertThat(writers).describedAs("durable decision and audit rows must both exist").isNotNull()
        assertThat(writers!!.first).isEqualTo(writers.second)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT deciding_party_id FROM sca_device_decisions WHERE challenge_id = ?",
            ).use { query ->
                query.setObject(1, fixture.challenge)
                query.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject("deciding_party_id", UUID::class.java)).isEqualTo(fixture.party)
                }
            }
        }
        val stored = event(fixture.challenge)
        val body = stored["body"]
        assertThat(body["eventId"].asText()).isEqualTo(stored["storedEventId"].asText())
        assertThat(body["credentialId"].asText()).isEqualTo(fixture.credential)
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(fixture.keys.public)
            update(Base64.getDecoder().decode(body["signedPayloadB64"].asText()))
            verify(Base64.getDecoder().decode(body["signatureB64"].asText()))
        }
        assertThat(verified).describedAs("audit evidence must preserve the exact signed bytes").isTrue()
        decide(fixture, 409)
        assertThat(outboxCount(fixture.challenge)).isEqualTo(1)
    }

    @Test
    fun `an audit write failure cannot leave a decision available for verification`() {
        val fixture = fixture()
        val constraint = "reject_${fixture.challenge.toString().replace("-", "")}"
        execute(
            "ALTER TABLE sca_outbox ADD CONSTRAINT $constraint " +
                "CHECK (event_type <> 'SCA_DEVICE_DECIDED' OR aggregate_id <> '${fixture.challenge}'::uuid)",
        )
        try {
            decide(fixture, 409)
            given().contentType("application/json").body(mapOf("partyId" to fixture.party))
                .post("/api/v1/sca/challenges/${fixture.challenge}/verify").then().statusCode(200)
                .body("status", equalTo("PENDING"))
            assertThat(writers(fixture.challenge)).isNull()
        } finally {
            execute("ALTER TABLE sca_outbox DROP CONSTRAINT $constraint")
        }
        decide(fixture, 200)
        assertThat(writers(fixture.challenge)).isNotNull()
    }

    @Test
    fun `authorization expiry retains evidence and cannot reopen the first decision`() {
        val fixture = fixture()
        decide(fixture, 200)
        execute(
            "UPDATE sca_device_decisions SET expires_at = decided_at + interval '1 microsecond' " +
                "WHERE challenge_id = '${fixture.challenge}'::uuid",
        )
        given().contentType("application/json").body(mapOf("partyId" to fixture.party))
            .post("/api/v1/sca/challenges/${fixture.challenge}/verify").then().statusCode(200)
            .body("status", equalTo("PENDING"))
        assertThat(writers(fixture.challenge)).isNotNull()
        decide(fixture, 409)
        assertThat(outboxCount(fixture.challenge)).isEqualTo(1)
    }

    private fun fixture(): DecisionFixture {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val fixture =
            DecisionFixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "decision-${UUID.randomUUID()}",
                generator.generateKeyPair(),
            )
        given().contentType("application/json").body(
            mapOf(
                "credentialId" to fixture.credential,
                "publicKey" to Base64.getEncoder().encodeToString(fixture.keys.public.encoded),
                "algorithm" to "ES256",
            ),
        ).post("/api/v1/sca/parties/${fixture.party}/devices").then().statusCode(201)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO sca_challenges " +
                    "(id, party_id, purpose, method, status, expires_at, created_at, attempt_count, max_attempts) " +
                    "VALUES (?, ?, 'LOGIN', 'PUSH_NOTIFICATION', 'PENDING', now() + interval '5 minutes', now(), 0, 3)",
            ).use { query ->
                query.setObject(1, fixture.challenge)
                query.setObject(2, fixture.party)
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
        }
        return fixture
    }

    private fun decide(fixture: DecisionFixture, expected: Int) {
        val payload = listOf(fixture.challenge.toString(), "APPROVED", "", "", "", "").joinToString("|")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(fixture.keys.private)
            update(payload.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        given().contentType("application/json").body(
            mapOf("credentialId" to fixture.credential, "decision" to "APPROVED", "signature" to signature),
        ).post("/api/v1/sca/challenges/${fixture.challenge}/decision").then().statusCode(expected)
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun decisionTableExists(): Boolean = dataSource.connection.use { connection ->
        connection.createStatement().use { query ->
            query.executeQuery("SELECT to_regclass('sca_device_decisions') IS NOT NULL").use { rows ->
                rows.next()
                rows.getBoolean(1)
            }
        }
    }

    private fun writers(id: UUID): Pair<Long, Long>? {
        if (!decisionTableExists()) return null
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT d.xmin::text::bigint, o.xmin::text::bigint FROM sca_device_decisions d " +
                    "JOIN sca_outbox o ON o.aggregate_id = d.challenge_id AND o.event_type = 'SCA_DEVICE_DECIDED' " +
                    "WHERE d.challenge_id = ?",
            ).use { query ->
                query.setObject(1, id)
                query.executeQuery().use { rows -> rows.writerPair() }
            }
        }
    }

    private fun event(id: UUID): JsonNode = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT jsonb_build_object('storedEventId', event_id, 'body', payload::jsonb) " +
                "FROM sca_outbox WHERE aggregate_id = ? AND event_type = 'SCA_DEVICE_DECIDED'",
        ).use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                check(rows.next())
                objectMapper.readTree(rows.getString(1))
            }
        }
    }

    private fun outboxCount(id: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM sca_outbox WHERE aggregate_id = ? AND event_type = 'SCA_DEVICE_DECIDED'",
        ).use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }
}

private data class DecisionFixture(val party: UUID, val challenge: UUID, val credential: String, val keys: KeyPair)

private fun ResultSet.writerPair(): Pair<Long, Long>? = if (next()) getLong(1) to getLong(2) else null
