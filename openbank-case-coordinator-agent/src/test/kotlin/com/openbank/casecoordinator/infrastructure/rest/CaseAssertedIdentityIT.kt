// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.rest

import com.openbank.casecoordinator.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * The agent identity a call ACTS AS must be authorised against the identity it PROVED (#4834).
 *
 * Driven over real HTTP so the principal and its roles are real: a unit test that constructs the
 * resource and hands it a mock `SecurityIdentity` cannot tell an enforced binding from a
 * decorative one, because the mock supplies whatever roles the test wants either way.
 *
 * The profile is what makes the defect observable at all. On the shipped defaults `openAgents`
 * holds exactly one entry, so the only string that passes `canOpenCase` is also the only string
 * anyone is bound to — the hole is real but inert, and no assertion can separate the two. This
 * profile grants the capability list a second chartered agent (`incident-triage`) and binds
 * `ROLE_OPERATOR` to only the first, which is precisely the state the config comment says is
 * coming ("swarm join/contribute grants are deliberate follow-up charter work").
 *
 * Read the pairs, not the single status. Temporal is disabled in `%test`. An authorised case-open
 * stops at 503; an authorised collaboration signal reaches the server-owned case lookup and stops
 * at 404 because this profile created no case row. Both prove the identity gate passed, while 403
 * proves it refused the asserted identity before either downstream decision.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(CaseAssertedIdentityIT.TwoCharteredAgentsProfile::class)
class CaseAssertedIdentityIT {

    private companion object {
        @Suppress("UNCHECKED_CAST")
        val SPEC: Map<String, Any?> by lazy {
            Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!
                .use { Yaml().load<Map<String, Any?>>(it) }
        }

        @Suppress("UNCHECKED_CAST")
        val SCHEMAS: Map<String, Map<String, Any?>> by lazy {
            (SPEC["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>
        }
    }

    /**
     * Literal values only, never a randomised id: a `QuarkusTestProfile` loads in a different
     * classloader from the test class, so anything computed in a companion object initialises
     * twice and the scheduler/config sees one value while the assertion sees another.
     */
    class TwoCharteredAgentsProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.case-coordinator.case.open-agents" to "case-coordinator,incident-triage",
            "openbank.case-coordinator.case.swarm-agents" to "case-coordinator,incident-triage",
            "openbank.case-coordinator.case.identity-bindings" to "ROLE_OPERATOR=case-coordinator",
        )
    }

    private fun openCase(openedBy: String) = given()
        .contentType(ContentType.JSON)
        .body(
            mapOf(
                "caseClass" to "INCIDENT_RESPONSE",
                "subjectRef" to "acct-4834",
                "openedBy" to openedBy,
                "dispositionTarget" to "alert-4834",
            ),
        )
        .`when`().post("/api/v1/case-coordinator/cases")
        .then()

    private fun signal(agentId: String) = given()
        .contentType(ContentType.JSON)
        .body(mapOf("type" to "contribute", "agentId" to agentId, "summary" to "s"))
        .`when`().post("/api/v1/case-coordinator/cases/case-incident-response-acct-4834/signals")
        .then()

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `openCase refuses an agent identity the caller's role is not bound to`() {
        openCase("incident-triage").statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `openCase still accepts the agent identity the caller's role IS bound to`() {
        // The must-ALLOW control: 503 is the Temporal-disabled stop, reached only after every
        // authorisation decision passed. Without it a blanket 403 would satisfy the test above.
        openCase("case-coordinator").statusCode(503)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `a denied openCase does not echo the asserted identity back`() {
        openCase("incident-triage")
            .statusCode(403)
            .body("error", not(containsString("incident-triage")))
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `signal refuses an agent identity the caller's role is not bound to`() {
        signal("incident-triage").statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `signal still accepts the agent identity the caller's role IS bound to`() {
        signal("case-coordinator").statusCode(404)
    }

    @Test
    @TestSecurity(user = "admin-1", roles = ["ROLE_ADMIN"])
    fun `a role with no binding at all may assert no agent identity`() {
        // Deny-by-default: the profile binds only ROLE_OPERATOR, so ROLE_ADMIN — which the
        // endpoint's @RolesAllowed still admits — can assert nothing. An unbound role must not
        // fall through to "allowed".
        openCase("case-coordinator").statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `invalid case-open request matches the documented validation body`() {
        val body = given().contentType(ContentType.JSON)
            .body(mapOf("caseClass" to "INCIDENT_RESPONSE"))
            .`when`().post("/api/v1/case-coordinator/cases")
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(body, documentedSchema("/api/v1/case-coordinator/cases", 400))
        assertThat(body["code"]).isEqualTo("VALIDATION_ERROR")
        assertThat(body).doesNotContainKey("error")
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `invalid signal request matches the documented validation body`() {
        val body = given().contentType(ContentType.JSON)
            .body(mapOf("type" to "unknown", "agentId" to "case-coordinator"))
            .`when`().post("/api/v1/case-coordinator/cases/case-unknown/signals")
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(body, documentedSchema("/api/v1/case-coordinator/cases/{caseId}/signals", 400))
        assertThat(body).doesNotContainKey("error")
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `resource denials retain their documented error body`() {
        val denied = openCase("incident-triage").statusCode(403).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(denied, documentedSchema("/api/v1/case-coordinator/cases", 403))
        val unavailable = openCase("case-coordinator").statusCode(503)
            .extract().jsonPath().getMap<String, Any?>("")
        assertConforms(unavailable, documentedSchema("/api/v1/case-coordinator/cases", 503))
    }

    @Test
    @TestSecurity(user = "viewer-1", roles = ["ROLE_VIEWER"])
    fun `missing case retains the documented error body`() {
        val body = given().`when`().get("/api/v1/case-coordinator/cases/case-missing-12078")
            .then().statusCode(404).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(body, documentedSchema("/api/v1/case-coordinator/cases/{caseId}", "get", 404))
    }

    @Test
    @TestSecurity(user = "viewer-1", roles = ["ROLE_VIEWER"])
    fun `role rejection before the resource has an empty 403 body`() {
        val raw = given().contentType(ContentType.JSON)
            .body(mapOf("caseClass" to "INCIDENT_RESPONSE"))
            .`when`().post("/api/v1/case-coordinator/cases")
            .then().statusCode(403).extract().asString()
        assertThat(raw).isEmpty()
    }

    @Test
    fun `the validation schema rejects the former error-only shape`() {
        assertThat(violations(mapOf("error" to "invalid"), "ProblemDetail")).isNotEmpty()
    }

    private fun assertConforms(body: Map<String, Any?>, schema: String) {
        assertThat(violations(body, schema)).describedAs("response %s against %s", body, schema).isEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun documentedSchema(path: String, status: Int): String = documentedSchema(path, "post", status)

    @Suppress("UNCHECKED_CAST")
    private fun documentedSchema(path: String, method: String, status: Int): String {
        val paths = SPEC["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path)[method] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val response = responses.getValue(status.toString())
        val content = response["content"] as Map<String, Map<String, Any?>>
        val media = content.getValue("application/json")
        return (media["schema"] as Map<String, String>).getValue("\$ref").substringAfterLast('/')
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(value: Any?, schemaName: String): List<String> {
        val schema = SCHEMAS.getValue(schemaName)
        val body = value as? Map<String, Any?> ?: return listOf("not an object")
        val props = schema["properties"] as Map<String, Map<String, Any?>>
        val failures = mutableListOf<String>()
        (schema["required"] as? List<String> ?: emptyList()).filterNot(body::containsKey)
            .forEach { failures += "$it is required" }
        if (schema["additionalProperties"] == false) {
            (body.keys - props.keys).forEach { failures += "$it is undeclared" }
        }
        body.forEach { (name, member) ->
            when (props[name]?.get("type")) {
                "string" -> if (member !is String) failures += "$name is not a string"
                "integer" -> if (member !is Int && member !is Long) failures += "$name is not an integer"
                "boolean" -> if (member !is Boolean) failures += "$name is not a boolean"
            }
        }
        return failures
    }
}
