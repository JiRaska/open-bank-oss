// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.application.port.out.ScaDecisionStore
import com.openbank.sca.domain.model.DeviceApprovalDecision
import com.openbank.sca.domain.model.DeviceDecisionType
import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(OutboxDispatchDisabledProfile::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScaLifecycleSafetyIT {
    @Inject lateinit var repository: ScaChallengeRepository

    @Inject lateinit var decisions: ScaDecisionStore

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `the durable store preserves the first signed decision`() {
        val first = DeviceApprovalDecision(
            seed("PENDING", OffsetDateTime.now().plusMinutes(5)),
            "test-credential",
            DeviceDecisionType.DENIED,
            "test-signature",
            OffsetDateTime.now(),
        )
        assertThat(onContext { decisions.record(first, 60) }).isTrue()
        assertThat(onContext { decisions.record(first.copy(decision = DeviceDecisionType.APPROVED), 60) }).isFalse()
        assertThat(onContext { decisions.find(first.challengeId) }?.decision).isEqualTo(DeviceDecisionType.DENIED)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a stale challenge write cannot erase consumption`() {
        val id = seed("COMPLETED", OffsetDateTime.now().plusMinutes(5))
        val stale = onContext { repository.findById(id) }!!
        given().contentType("application/json").body(mapOf("partyId" to stale.partyId))
            .post("/api/v1/sca/challenges/$id/consume").then().statusCode(200)
        assertThatThrownBy { onContext { repository.save(stale) } }
            .isInstanceOf(com.openbank.sca.application.port.out.ScaConcurrentUpdateException::class.java)
        assertThat(onContext { repository.findById(id) }?.consumedAt).isNotNull()
        given().contentType("application/json").body(mapOf("partyId" to stale.partyId))
            .post("/api/v1/sca/challenges/$id/consume").then().statusCode(409)
    }

    @Test
    fun `the database refuses consumption of an expired approved challenge`() {
        val id = seed("COMPLETED", OffsetDateTime.now().minusSeconds(1))
        assertThat(onContext { repository.markConsumed(id) }).isFalse()
    }

    @Test
    fun `the database refuses consumption of an unapproved challenge`() {
        val id = seed("PENDING", OffsetDateTime.now().plusMinutes(5))
        assertThat(onContext { repository.markConsumed(id) }).isFalse()
    }

    @Test
    fun `concurrent verification cannot overwrite a failed attempt`() {
        val id = seed("PENDING", OffsetDateTime.now().plusMinutes(5))
        val first = onContext { repository.findById(id) }!!
        val second = onContext { repository.findById(id) }!!
        onContext { repository.save(first.fail("Invalid OTP", OffsetDateTime.now())) }
        assertThatThrownBy { onContext { repository.save(second.fail("Invalid OTP", OffsetDateTime.now())) } }
            .isInstanceOf(com.openbank.sca.application.port.out.ScaConcurrentUpdateException::class.java)
        assertThat(onContext { repository.findById(id) }?.attemptCount).isEqualTo(1)
    }

    @Test
    fun `concurrent database claims acknowledge exactly one decision`(): Unit = runBlocking {
        val id = seed("PENDING", OffsetDateTime.now().plusMinutes(5))
        val results = (1..8).map { index ->
            async(Dispatchers.IO) {
                index to onContext {
                    decisions.record(
                        DeviceApprovalDecision(
                            id,
                            "test-$index",
                            DeviceDecisionType.APPROVED,
                            "signature-$index",
                            OffsetDateTime.now(),
                        ),
                        60,
                    )
                }
            }
        }.awaitAll()
        val accepted = results.filter { it.second }
        assertThat(accepted).hasSize(1)
        assertThat(onContext { decisions.find(id) }?.credentialId).isEqualTo("test-${accepted.single().first}")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `consume HTTP responses match the published contract`() {
        val spec = javaClass.getResourceAsStream("/openapi.yaml")!!.use { Yaml().load<Map<String, Any>>(it) }
        val paths = spec["paths"] as Map<*, *>
        val path = "/api/v1/sca/challenges/{id}/consume"
        val operation = (paths[path] as Map<*, *>)["post"] as Map<*, *>
        val responses = operation["responses"] as Map<*, *>
        assertThat(responses.keys).containsAll(listOf("200", "400", "409", "422"))
        assertThat(paths["/api/v1/sca/parties/{partyId}/challenges/pending"]).isNotNull()
        val id = seed("COMPLETED", OffsetDateTime.now().plusMinutes(5))
        val challenge = onContext { repository.findById(id) }!!
        val response = given().contentType("application/json").body(mapOf("partyId" to challenge.partyId))
            .post(path.replace("{id}", id.toString())).then().statusCode(200).extract().jsonPath()
            .getMap<String, Any?>("$")
        val schemas = (spec["components"] as Map<*, *>)["schemas"] as Map<*, *>
        val properties = (schemas["ScaChallengeResponse"] as Map<*, *>)["properties"] as Map<*, *>
        assertThat(properties.keys).containsAll(response.keys)
        assertThat(response["consumedAt"]).isNotNull()
        given().contentType("application/json").body(mapOf("partyId" to challenge.partyId))
            .post(path.replace("{id}", id.toString())).then().statusCode(409)
        for ((status, expiry, expected) in listOf(
            Triple("COMPLETED", OffsetDateTime.now().minusSeconds(1), 422),
            Triple("PENDING", OffsetDateTime.now().plusMinutes(5), 400),
        )) {
            val rejected = seed(status, expiry)
            val party = onContext { repository.findById(rejected) }!!.partyId
            given().contentType("application/json").body(mapOf("partyId" to party))
                .post(path.replace("{id}", rejected.toString())).then().statusCode(expected)
            assertThat(onContext { repository.findById(rejected) }?.consumedAt).isNull()
            if (status == "PENDING") {
                given().get("/api/v1/sca/parties/$party/challenges/pending").then().statusCode(200)
                    .body("id", org.hamcrest.Matchers.hasItem(rejected.toString()))
            }
        }
    }

    private fun seed(status: String, expires: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO sca_challenges " +
                    "(id, party_id, purpose, method, status, expires_at, created_at, attempt_count, max_attempts) " +
                    "VALUES (?, ?, 'LOGIN', 'TOTP', ?, ?, now(), 0, 3)",
            ).use { query ->
                query.setObject(1, id)
                query.setObject(2, UUID.randomUUID())
                query.setString(3, status)
                query.setObject(4, expires)
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
        }
        return id
    }

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { block() }
    }
}
