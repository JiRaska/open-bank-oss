// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.contract

import com.openbank.copilot.application.port.out.ProposalTokenStore
import com.openbank.copilot.domain.ProposalToken
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import org.yaml.snakeyaml.Yaml
import java.time.Instant
import java.util.UUID

/** Real Redis-compatible adapter proof: its TTL may outlive the token's expiresAt. */
@QuarkusTest
@QuarkusTestResource(CopilotValkeyTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CopilotRedisErrorBodyConformanceIT.RedisTokenProfile::class)
class CopilotRedisErrorBodyConformanceIT {
    class RedisTokenProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("copilot.token-store" to "redis")
    }

    @Inject
    lateinit var tokens: ProposalTokenStore

    @Test
    @TestSecurity(user = "copilot-audit-customer")
    fun `Redis returns expired token before TTL and resource answers 422 error body`() {
        val token = ProposalToken(
            id = UUID.randomUUID(),
            toolName = "propose_payment",
            params = emptyMap(),
            expiresAt = Instant.now().minusSeconds(60),
            customerId = "copilot-audit-customer",
        )
        tokens.save(token)
        try {
            assertThat(tokens.find(token.id)).describedAs("real Redis adapter must return expired row before TTL")
                .isNotNull()
            val body = given().contentType(ContentType.JSON)
                .`when`().post("/api/v1/copilot/actions/${token.id}/confirm")
                .then().statusCode(422).extract().jsonPath().getMap<String, Any?>("")
            assertThat(documented422Required()).isSubsetOf(body.keys)
            assertThat(body["error"]).isEqualTo("PROPOSAL_EXPIRED")
        } finally {
            tokens.delete(token.id)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun documented422Required(): List<String> {
        val spec = Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!
            .use { Yaml().load<Map<String, Any?>>(it) }
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue("/copilot/actions/{tokenId}/confirm")["post"] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val content = responses.getValue("422")["content"] as Map<String, Map<String, Any?>>
        val schemaRef = (content.getValue("application/json")["schema"] as Map<String, String>)
            .getValue("\$ref").substringAfterLast('/')
        val schemas = (spec["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>
        return schemas.getValue(schemaRef)["required"] as List<String>
    }
}

class CopilotValkeyTestResource : QuarkusTestResourceLifecycleManager {
    private companion object {
        const val VALKEY_IMAGE = "valkey/valkey:8-alpine"
        const val VALKEY_PORT = 6379
    }

    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Valkey IT")
        }
        val instance = GenericContainer(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(VALKEY_PORT)
        redis = instance
        instance.start()
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started")
        return mapOf("quarkus.redis.hosts" to "redis://${instance.host}:${instance.getMappedPort(VALKEY_PORT)}")
    }

    override fun stop() {
        redis?.let {
            it.stop()
            TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped")
        }
    }
}
