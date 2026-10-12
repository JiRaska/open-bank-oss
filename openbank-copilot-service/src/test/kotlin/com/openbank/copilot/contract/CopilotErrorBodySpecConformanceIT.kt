// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.contract

import com.openbank.copilot.application.port.out.ProposalTokenStore
import com.openbank.copilot.domain.ProposalToken
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.time.Instant
import java.util.UUID

/**
 * #12078: the error member is legitimate on the copilot resource's explicit error responses.
 * Verify served HTTP bodies against the referenced response schema, not just its YAML text.
 */
@QuarkusTest
@TestProfile(CopilotErrorBodySpecConformanceIT.MemoryTokenProfile::class)
class CopilotErrorBodySpecConformanceIT {
    class MemoryTokenProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("copilot.token-store" to "memory")
    }

    @Inject
    lateinit var tokens: ProposalTokenStore

    private companion object {
        @Suppress("UNCHECKED_CAST")
        val SPEC: Map<String, Any?> by lazy {
            Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!
                .use { Yaml().load<Map<String, Any?>>(it) }
        }
    }

    @Test
    @TestSecurity(user = "copilot-audit-customer")
    fun `disabled chat returns the documented error body`() {
        val response = given().contentType(ContentType.JSON)
            .body(mapOf("message" to "hello"))
            .`when`().post("/api/v1/copilot/chat")
            .then().statusCode(501).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(response, "/copilot/chat", 501)
    }

    @Test
    fun `unauthenticated chat carries documented shared authentication body`() {
        val response = given().contentType(ContentType.JSON)
            .body(mapOf("message" to "hello"))
            .`when`().post("/api/v1/copilot/chat")
            .then().statusCode(401).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(response, "/copilot/chat", 401)
        assertThat(response["code"]).isEqualTo("UNAUTHORIZED")
        assertThat(response).doesNotContainKey("error")
    }

    @Test
    fun `unauthenticated confirmation carries documented shared authentication body`() {
        val response = given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/copilot/actions/not-a-uuid/confirm")
            .then().statusCode(401).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(response, "/copilot/actions/{tokenId}/confirm", 401)
        assertThat(response["code"]).isEqualTo("UNAUTHORIZED")
        assertThat(response).doesNotContainKey("error")
    }

    @Test
    @TestSecurity(user = "copilot-audit-customer")
    fun `malformed confirmation token returns the documented error body`() {
        val response = given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/copilot/actions/not-a-uuid/confirm")
            .then().statusCode(404).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(response, "/copilot/actions/{tokenId}/confirm", 404)
        assertThat(response["error"]).isEqualTo("PROPOSAL_NOT_FOUND")
    }

    @Test
    @TestSecurity(user = "copilot-audit-customer")
    fun `expired token evicted by the store returns 404 with the documented error body`() {
        val token = ProposalToken(
            id = UUID.randomUUID(),
            toolName = "propose_payment",
            params = emptyMap(),
            expiresAt = Instant.now().minusSeconds(60),
            customerId = "copilot-audit-customer",
        )
        tokens.save(token)
        val response = given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/copilot/actions/${token.id}/confirm")
            .then().statusCode(404).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(response, "/copilot/actions/{tokenId}/confirm", 404)
        assertThat(response["error"]).isEqualTo("PROPOSAL_NOT_FOUND")
    }

    @Test
    @TestSecurity(user = "copilot-audit-customer")
    fun `another customer's token returns 403 with the documented error body`() {
        val token = ProposalToken(
            id = UUID.randomUUID(),
            toolName = "propose_payment",
            params = emptyMap(),
            expiresAt = Instant.now().plusSeconds(60),
            customerId = "another-customer",
        )
        tokens.save(token)
        try {
            val response = given().contentType(ContentType.JSON)
                .`when`().post("/api/v1/copilot/actions/${token.id}/confirm")
                .then().statusCode(403).extract().jsonPath().getMap<String, Any?>("")
            assertConforms(response, "/copilot/actions/{tokenId}/confirm", 403)
            assertThat(response["error"]).isEqualTo("PROPOSAL_NOT_FOUND")
        } finally {
            tokens.delete(token.id)
        }
    }

    @Test
    fun `the documented schema rejects a mapper envelope lacking error`() {
        val schema = documentedSchemas("/copilot/chat", 501).single()
        val required = schema["required"] as List<*>
        assertThat(required).contains("error")
        assertThat(mapOf("code" to "VALIDATION_ERROR").keys).doesNotContain("error")
    }

    @Suppress("UNCHECKED_CAST")
    private fun documentedSchemas(path: String, status: Int): List<Map<String, Any?>> {
        val paths = SPEC["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path)["post"] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val content = responses.getValue(status.toString())["content"] as Map<String, Map<String, Any?>>
        val schema = content.getValue("application/json")["schema"] as Map<String, Any?>
        val refs = (schema["oneOf"] as? List<Map<String, String>>)
            ?: listOf(schema as Map<String, String>)
        val schemas = (SPEC["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>
        return refs.map { schemas.getValue(it.getValue("\$ref").substringAfterLast('/')) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertConforms(body: Map<String, Any?>, path: String, status: Int) {
        val candidates = documentedSchemas(path, status)
        val failures = candidates.map { schema -> violations(body, schema) }
        assertThat(failures).describedAs("response %s against %s %s", body, path, status)
            .anySatisfy { assertThat(it).isEmpty() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(body: Map<String, Any?>, schema: Map<String, Any?>): List<String> {
        val required = schema["required"] as List<String>
        val props = schema["properties"] as Map<String, Map<String, Any?>>
        val errors = required.filterNot(body::containsKey).map { "$it is required" }.toMutableList()
        if (schema["additionalProperties"] == false) {
            (body.keys - props.keys).forEach { errors += "$it is undeclared" }
        }
        body.forEach { (name, value) ->
            val type = props[name]?.get("type")
            if (type == "string" && value !is String) errors += "$name is not a string"
            if (type == "integer" && value !is Int && value !is Long) errors += "$name is not an integer"
            if (type == "boolean" && value !is Boolean) errors += "$name is not a boolean"
        }
        return errors
    }
}
