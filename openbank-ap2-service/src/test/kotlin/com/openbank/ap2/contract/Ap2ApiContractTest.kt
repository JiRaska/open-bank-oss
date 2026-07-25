// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.quarkus.arc.Arc
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HEAD
import jakarta.ws.rs.OPTIONS
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Method
import jakarta.enterprise.inject.Any as CdiAny

/**
 * Spec-conformance contract test for the AP2 verify surface (ADR-0193, issue #2255 dimension C3).
 *
 * ## Why this is not a Pact
 *
 * openbank-ap2-service has no in-repo consumer and calls nothing in-repo — its caller is an external
 * agent wallet. A consumer-driven pact needs a real consumer to drive it, and a pact written against
 * a fictional consumer pins nothing: it would only prove that a test agrees with itself. So the
 * counterparty here is the **committed `openapi.yaml`**, which is what an external integrator
 * actually codes against, and the assertion is that the running service and that document describe
 * the same API.
 *
 * This service scored C3=2 before #2291 on a **false positive**: the old scorer matched the bare word
 * "contract" anywhere under `src/test`, and this tree happened to contain one. Nothing checked the
 * spec against the implementation. Older reports claiming ap2 is covered are wrong.
 *
 * ## The spec is PARSED, never grepped
 *
 * Every expectation below is derived from `openapi.yaml` by JSON-Pointer navigation, so the test
 * cannot pass by matching prose. A `contains("valid")` over the YAML text — the failure mode #2291
 * documents — sees neither of the drift directions this catches.
 *
 * ## Bidirectional, at two levels
 *
 * Direction two is the one that catches a live defect class in this repo: copilot-service turned out
 * to serve a money-path confirm endpoint that was absent from its spec, so checking only "everything
 * declared is served" would have called it healthy.
 *
 *  - **Operations.** The declared `(method, path)` set is compared for *exact* set equality with the
 *   set the service really serves. The served set is discovered by reflection over the JAX-RS
 *   resource beans in the CDI container — not by a list maintained here, which would drift the same
 *   way the spec does. A new endpoint therefore fails this test until it is documented.
 *  - **Response properties.** The 200 verdict's property names are compared for exact set equality
 *   with what the endpoint really emits, and each declared property's JSON type is checked against
 *   the live value.
 *
 * `servers:` is resolved before comparing. ap2 declares none, so `/ap2/verify` is the literal served
 * path — but the resolution is done explicitly rather than assumed, because reading `paths:` alone is
 * exactly how copilot's spec was misread as broken when its `servers: [- url: /api/v1]` made it
 * correct.
 *
 * The served set is scoped to this service's own package. The four `com.openbank.libs.*` resources
 * every service inherits from the shared runtime (`/api/v1/info`, `/api/v1/config`,
 * `/q/openbank/sbom`, `/q/openbank/docs`) are fleet platform surface that no service's `openapi.yaml`
 * declares; folding them in would make this assertion fail everywhere for a reason that has nothing
 * to do with ap2's contract.
 *
 * ## Driven for real
 *
 * `@QuarkusTest` + RestAssured rather than calling the endpoint as a Kotlin object, because the wire
 * JSON is what the contract is about: Jackson property naming, enum spelling and nullability only
 * exist after serialisation. In particular a Kotlin `is`/`has`-prefixed property does NOT serialise
 * the way plain Jackson would (jackson-module-kotlin keeps the constructor-parameter name, so
 * `isFoo` stays `isFoo` — measured on #2290), so the JSON name is read off the running endpoint here
 * and never guessed. [com.openbank.ap2.Ap2VerifyEndpointTest] already covers the decision logic
 * in-process; this test covers the wire format.
 */
@QuarkusTest
class Ap2ApiContractTest {

    private val spec: JsonNode = YAMLMapper().readTree(File(SPEC_PATH))

    private val verifyPost: JsonNode
        get() = spec.at("/paths/~1ap2~1verify/post").also {
            assertThat(it.isMissingNode)
                .describedAs("%s must declare POST /ap2/verify", SPEC_PATH)
                .isFalse()
        }

    // ---------------------------------------------------------------- operations, both directions

    @Test
    fun `the spec declares exactly the operations the service serves`() {
        val declared = declaredOperations()
        val served = servedOperations()

        assertThat(declared)
            .describedAs("operations in %s, resolved against servers:, vs the JAX-RS surface", SPEC_PATH)
            .isNotEmpty()
        // Exact set equality is both directions at once: a declared-but-unserved operation is a lie
        // to integrators, and a served-but-undeclared one is the copilot defect class.
        assertThat(served)
            .describedAs("served operations vs declared — extras are undocumented endpoints")
            .containsExactlyInAnyOrderElementsOf(declared)
    }

    @Test
    fun `every declared operation answers on the wire, so no declared path is a 404`() {
        declaredOperations().forEach { operation ->
            val (method, path) = operation.split(' ', limit = 2)
            assertThat(method).isEqualTo("POST") // the only verb this surface has; see the set above
            val status = postVerify(path, TestPolicyDecisionPoint.ALLOWED_KIND).then().extract().statusCode()
            // 404 = the path is not routed at all; 405 = it is routed but not for this verb. Both mean
            // the spec documents an operation the service does not serve. 405 is the likelier of the
            // two whenever a sibling path template overlaps, so neither is enough on its own.
            assertThat(status)
                .describedAs("declared operation '%s' must be routed (got %s)", operation, status)
                .isNotIn(404, 405)
        }
    }

    // ------------------------------------------------------------ response schema, both directions

    @Test
    fun `the verdict body carries exactly the properties the spec declares`() {
        val body = verdictBody()

        assertThat(body.keys)
            .describedAs("live verdict properties vs the 200 schema in %s", SPEC_PATH)
            .containsExactlyInAnyOrderElementsOf(declaredVerdictProperties().keys)
    }

    @Test
    fun `each declared verdict property has the JSON type the spec declares`() {
        val body = verdictBody()

        declaredVerdictProperties().forEach { (name, schema) ->
            val declaredType = schema.get("type").asText()
            val actual = body[name]
            assertThat(actual).describedAs("verdict property '%s' is absent", name).isNotNull()
            val matches = when (declaredType) {
                "boolean" -> actual is Boolean
                "array" -> actual is List<*>
                "object" -> actual is Map<*, *>
                "string" -> actual is String
                "number", "integer" -> actual is Number
                else -> error("unhandled declared type '$declaredType' for '$name' — extend this test")
            }
            assertThat(matches)
                .describedAs("'%s' is declared %s but the wire value was %s", name, declaredType, actual)
                .isTrue()
        }
    }

    // ------------------------------------------------------------------------- request-side schema

    @Test
    fun `every mandate kind and algorithm the spec declares is accepted on the wire`() {
        val mandateSchema = verifyPost.at("/requestBody/content/application~1json/schema/properties/mandate")
        val kinds = mandateSchema.at("/properties/kind/enum").map { it.asText() }
        val algorithms = mandateSchema.at("/properties/algorithm/enum").map { it.asText() }
        assertThat(kinds).describedAs("declared mandate kinds").isNotEmpty()
        assertThat(algorithms).describedAs("declared signature algorithms").isNotEmpty()

        // A declared enum value the service cannot deserialise answers 400 — the spelling-drift class
        // (the spec keeps a kind the enum dropped, or vice versa) that no schema-free test can see.
        kinds.forEach { kind ->
            val status = postVerify(VERIFY_PATH, kind).then().extract().statusCode()
            assertThat(status)
                .describedAs("declared mandate kind '%s' must deserialise (200 allowed / 403 denied)", kind)
                .isIn(200, 403)
        }
        algorithms.forEach { algorithm ->
            val status = postVerify(VERIFY_PATH, TestPolicyDecisionPoint.ALLOWED_KIND, algorithm)
                .then().extract().statusCode()
            assertThat(status)
                .describedAs("declared algorithm '%s' must deserialise", algorithm)
                .isEqualTo(200)
        }
    }

    @Test
    fun `a request missing a spec-required top-level field is rejected, not silently defaulted`() {
        val required = verifyPost.at("/requestBody/content/application~1json/schema/required").map { it.asText() }
        assertThat(required).contains("mandate", "payment")

        // Fail-closed is the whole point of an authorization-evidence surface: a 200 here would be a
        // verdict rendered over an absent mandate.
        val status = given()
            .contentType(ContentType.JSON)
            .header(AGENT_HEADER, AGENT_ID)
            .body(mapOf("payment" to payment))
            .post(VERIFY_PATH)
            .then().extract().statusCode()
        assertThat(status).describedAs("POST %s without the required 'mandate'", VERIFY_PATH).isNotEqualTo(200)
    }

    @Test
    fun `the spec documents the fail-closed denial status the endpoint really returns`() {
        assertThat(verifyPost.at("/responses/403").isMissingNode)
            .describedAs("%s must document the policy-denied response", SPEC_PATH)
            .isFalse()

        postVerify(VERIFY_PATH, TestPolicyDecisionPoint.DENIED_KIND).then().statusCode(403)
    }

    // ------------------------------------------------------------------------------------ helpers

    /** `(METHOD, path)` pairs the spec declares, each resolved against the `servers:` base path. */
    private fun declaredOperations(): Set<String> {
        // Read `servers:` explicitly. A spec whose paths look wrong is often a spec whose server base
        // path was never applied — the mistake #2255 nearly repeated on copilot-service.
        val base = spec.at("/servers/0/url").let { if (it.isMissingNode) "" else it.asText() }
            .trimEnd('/')
        return spec.at("/paths").properties().flatMap { (rawPath, pathItem) ->
            pathItem.fieldNames().asSequence()
                .map { it.uppercase() }
                .filter { it in HTTP_METHODS }
                .map { method -> "$method ${normalise(base + rawPath)}" }
                .toList()
        }.toSet()
    }

    /**
     * `(METHOD, path)` pairs the running service really serves, discovered from the CDI container:
     * every JAX-RS resource in Quarkus is a bean, so this cannot miss one the way a hand-kept list
     * would. Scoped to this service's package — see the class KDoc on the shared `com.openbank.libs`
     * platform resources.
     */
    private fun servedOperations(): Set<String> =
        Arc.container().beanManager().getBeans(Any::class.java, CdiAny.Literal.INSTANCE)
            .asSequence()
            .map { it.beanClass }
            .filter { it.name.startsWith(SERVICE_PACKAGE) && it.getAnnotation(Path::class.java) != null }
            .flatMap { resource ->
                val basePath = resource.getAnnotation(Path::class.java).value
                resource.declaredMethods.asSequence().mapNotNull { method ->
                    httpMethodOf(method)?.let { verb ->
                        val sub = method.getAnnotation(Path::class.java)?.value ?: ""
                        "$verb ${normalise("$basePath/$sub")}"
                    }
                }
            }
            .toSet()

    private fun httpMethodOf(method: Method): String? = when {
        method.getAnnotation(GET::class.java) != null -> "GET"
        method.getAnnotation(POST::class.java) != null -> "POST"
        method.getAnnotation(PUT::class.java) != null -> "PUT"
        method.getAnnotation(DELETE::class.java) != null -> "DELETE"
        method.getAnnotation(PATCH::class.java) != null -> "PATCH"
        method.getAnnotation(HEAD::class.java) != null -> "HEAD"
        method.getAnnotation(OPTIONS::class.java) != null -> "OPTIONS"
        else -> null
    }

    /** Collapse duplicate separators and drop a trailing one, so `/a//b/` and `/a/b` compare equal. */
    private fun normalise(path: String): String = "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/")

    /** The 200 verdict schema's declared properties, name -> schema node. */
    private fun declaredVerdictProperties(): Map<String, JsonNode> =
        verifyPost.at("/responses/200/content/application~1json/schema/properties")
            .properties().associate { it.key to it.value }

    private fun verdictBody(): Map<String, Any> = postVerify(VERIFY_PATH, TestPolicyDecisionPoint.ALLOWED_KIND)
        .then().statusCode(200)
        .extract().body().jsonPath().getMap("")

    private fun postVerify(path: String, kind: String, algorithm: String = "ED25519") = given()
        .contentType(ContentType.JSON)
        .header(AGENT_HEADER, AGENT_ID)
        .body(mapOf("mandate" to mandate(kind, algorithm), "payment" to payment))
        .post(path)

    private fun mandate(kind: String, algorithm: String) = mapOf(
        "kind" to kind,
        "issuer" to "did:example:issuer-1",
        "subject" to "cust-1",
        "algorithm" to algorithm,
        "signingInput" to "header.payload",
        "signatureB64" to "c2lnbmF0dXJl",
        "constraints" to mapOf(
            "payee" to PAYEE,
            "amountCapMinor" to 100_00,
            "currency" to "CZK",
            "expiresAt" to "2026-12-31T00:00:00Z",
            "singleUse" to false,
        ),
    )

    /** Inside every constraint, so the verdict is shaped by the signature stage alone. */
    private val payment = mapOf(
        "payee" to PAYEE,
        "amountMinor" to 50_00,
        "currency" to "CZK",
        "at" to "2026-06-01T00:00:00Z",
    )

    private companion object {
        const val SPEC_PATH = "src/main/resources/openapi.yaml"
        const val SERVICE_PACKAGE = "com.openbank.ap2."
        const val VERIFY_PATH = "/ap2/verify"
        const val AGENT_HEADER = "X-Agent-Id"
        const val AGENT_ID = "agent:contract-test"
        const val PAYEE = "CZ6508000000192000145399"
        val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
    }
}
