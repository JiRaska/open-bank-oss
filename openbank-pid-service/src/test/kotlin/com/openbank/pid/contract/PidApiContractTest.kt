// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.quarkus.arc.Arc
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
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
import java.net.URI
import jakarta.enterprise.inject.Any as CdiAny

/**
 * Spec-conformance contract test for pid-service (eIDAS 2.0 / ADR-0072 / ADR-0094; issue #2255
 * dimension C3).
 *
 * ## Why this is not a Pact
 *
 * pid-service has no in-repo consumer and calls nothing in-repo — its callers are the admin cockpit
 * and, for the EUDI surface, external wallets. A consumer-driven pact needs a real consumer to drive
 * it, and one written against a fictional consumer pins nothing: it would prove only that a test
 * agrees with itself. So the counterparty is the **committed `openapi.yaml`**, which is what an
 * external integrator codes against.
 *
 * Before #2291 this service scored C3=2 on a **false positive** — the old scorer matched the bare
 * word "contract" anywhere under `src/test`. Nothing compared the spec to the implementation.
 *
 * The spec is **parsed**, never grepped: every expectation is derived by JSON-Pointer navigation with
 * `$ref` resolution, so no check can pass by matching prose.
 *
 * ## Which of the 29 declared paths this pins, and why
 *
 * The **operation inventory is checked for all of them** — that assertion is cheap, needs no
 * per-endpoint fixture, and is the one that catches the defect class that actually shipped here:
 * copilot-service served a money-path confirm endpoint absent from its spec, so a one-directional
 * "everything declared is served" check would have called it healthy. Driving all 29 bodies would
 * instead need seeded parties, cases, credential offers, status lists and wallet keys — fixture
 * surface that would make this test fragile without pinning more contract.
 *
 * Two paths are driven end-to-end, chosen for consequence rather than convenience, and both are
 * reachable on an empty Flyway schema with no seeding:
 *
 *  1. **`POST /api/v1/parties/resolve`** — the ADR-0072 identity gate. Its own description states
 *   "Only a NO_MATCH decision permits creating a new party", so the entire duplicate-party guard is a
 *   caller parsing `decision` out of this body. A renamed field or a re-spelled enum value here
 *   creates duplicate identities rather than an error. It is also the endpoint whose two shipped
 *   defects (#1308 the missing reactive session, #1301 the wrong required role) were invisible to
 *   every probe that did not make an authenticated request reaching the DB.
 *  2. **`GET /.well-known/openid-credential-issuer`** — the OpenID4VCI discovery document. It is
 *   `@PermitAll` and it is the *first* thing an external EUDI wallet fetches, before it holds any
 *   credential, so it is the one path on this service where a contract break is visible to a third
 *   party immediately and with no way for us to coordinate the fix.
 *
 * ## `servers:` is resolved, not assumed
 *
 * pid declares `servers: [- url: http://localhost:8128]`. That is an **absolute** URL whose path
 * component is empty, so the declared paths are already served as written — but the base is extracted
 * via [URI.getPath] rather than string-concatenated, because concatenating the whole URL would
 * produce nonsense and reading `paths:` alone is exactly how copilot's spec was misread as broken
 * when its `servers: [- url: /api/v1]` made it correct.
 *
 * ## Nullability, not blunt set equality
 *
 * Unlike a spec with no optional fields, pid marks `partyId`/`caseId`/`candidates` `nullable: true`.
 * So the rule asserted is the accurate one: every **non-nullable** declared property must be present
 * on the wire, no **undeclared** property may appear, and a nullable declared property may be absent.
 * Blunt set equality would fail or pass here for reasons about Jackson's null-inclusion setting
 * rather than about the contract.
 *
 * ## Driven for real
 *
 * `@QuarkusTest` + RestAssured, on a Testcontainers Postgres with the in-memory Kafka connector — the
 * same boot recipe as [com.openbank.pid.integration.PidBootSmokeIT], which proved `/resolve` reaches
 * the DB and answers `NO_MATCH` on an empty schema. The wire JSON is what the contract is about:
 * Jackson naming, enum spelling and nullability only exist after serialisation. No JSON name is
 * guessed — jackson-module-kotlin keeps the constructor-parameter name, so an `is`-prefixed property
 * stays `isFoo` rather than becoming `foo` (measured on #2290); the names here are read off the
 * running endpoint.
 */
@QuarkusTest
@QuarkusTestResource(PidApiContractTest.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.pid.it.PostgresTestResource::class)
class PidApiContractTest {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("party-events-out", "pid-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    private val spec: JsonNode = YAMLMapper().readTree(File(SPEC_PATH))

    // ---------------------------------------------------------------- operations, both directions

    @Test
    fun `the spec declares exactly the operations the service serves`() {
        val declared = declaredOperations()
        val served = servedOperations()

        assertThat(declared)
            .describedAs("operations declared in %s, resolved against servers:", SPEC_PATH)
            .isNotEmpty()
        // Exact set equality is both directions at once: a declared-but-unserved operation is a lie
        // to integrators; a served-but-undeclared one is the copilot defect class.
        assertThat(served)
            .describedAs("served JAX-RS operations vs declared — extras are undocumented endpoints")
            .containsExactlyInAnyOrderElementsOf(declared)
    }

    // ------------------------------------------------------- POST /api/v1/parties/resolve (ADR-0072)

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_OPERATOR"])
    fun `the resolve decision body declares every property it emits, and emits every required one`() {
        val body = resolveBody()
        val schema = responseSchema(RESOLVE_PATH, "post", "200")
        val properties = schema.get("properties").properties().associate { it.key to it.value }
        val mandatory = properties.filterValues { !it.path("nullable").asBoolean(false) }.keys

        assertThat(body.keys)
            .describedAs("undeclared properties on %s — the spec would not tell an integrator about them", RESOLVE_PATH)
            .isSubsetOf(properties.keys)
        assertThat(body.keys)
            .describedAs("non-nullable declared properties missing from the wire")
            .containsAll(mandatory)
    }

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_OPERATOR"])
    fun `each resolve property present on the wire has the JSON type the spec declares`() {
        val body = resolveBody()
        val properties = responseSchema(RESOLVE_PATH, "post", "200").get("properties")

        properties.properties().forEach { (name, propertySchema) ->
            val actual = body[name] ?: return@forEach // nullable and absent — covered by the test above
            val declaredType = propertySchema.get("type").asText()
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

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_OPERATOR"])
    fun `the decision the endpoint returns is one the spec's enum declares`() {
        val declaredDecisions = responseSchema(RESOLVE_PATH, "post", "200")
            .at("/properties/decision/enum").map { it.asText() }
        assertThat(declaredDecisions)
            .describedAs("declared resolve decisions")
            .isNotEmpty()

        // An unlisted decision value is the drift that creates duplicate identities: the caller's
        // `== "NO_MATCH"` branch silently stops matching and the create path is never taken.
        assertThat(resolveBody()["decision"])
            .describedAs("live decision vs the enum in %s", SPEC_PATH)
            .isIn(declaredDecisions as Iterable<kotlin.Any>)
    }

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_OPERATOR"])
    fun `a resolve request missing every spec-required field is rejected, not silently resolved`() {
        val required = requestSchema(RESOLVE_PATH, "post").get("required").map { it.asText() }
        assertThat(required).describedAs("declared required fields on %s", RESOLVE_PATH).isNotEmpty()

        // A 200 on an empty body would be a NO_MATCH over nothing — which, per this endpoint's own
        // description, is the one decision that permits creating a new party.
        val status = given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post(RESOLVE_PATH)
            .then().extract().statusCode()
        assertThat(status).describedAs("POST %s with an empty body", RESOLVE_PATH).isNotEqualTo(200)
    }

    // -------------------------------------------- GET /.well-known/openid-credential-issuer (EUDI)

    @Test
    fun `the public OpenID4VCI discovery document is declared and answers unauthenticated`() {
        assertThat(spec.at("/paths/${pointer(WELL_KNOWN_PATH)}/get").isMissingNode)
            .describedAs("%s must declare GET %s", SPEC_PATH, WELL_KNOWN_PATH)
            .isFalse()

        // No @TestSecurity on purpose: the resource is @PermitAll because a wallet reads it before it
        // holds any credential. A 401 here would be a contract break invisible to every
        // authenticated test.
        val body = given()
            .get(WELL_KNOWN_PATH)
            .then().statusCode(200)
            .extract().body().jsonPath().getMap<String, kotlin.Any>("")

        // OpenID4VCI's own required metadata keys. Asserted as literals rather than read from the
        // spec because the spec declares NO schema for this 200 (see the PR body) — pinning them
        // here is what makes that gap non-silent.
        assertThat(body.keys)
            .describedAs("OpenID4VCI issuer metadata keys")
            .contains("credential_issuer", "credential_endpoint", "token_endpoint", "jwks")
    }

    @Test
    fun `the endpoints the discovery document advertises are themselves declared in the spec`() {
        val body = given().get(WELL_KNOWN_PATH).then().statusCode(200)
            .extract().body().jsonPath().getMap<String, kotlin.Any>("")
        val issuer = body["credential_issuer"] as String
        val declaredPaths = spec.at("/paths").fieldNames().asSequence().toSet()

        // The discovery document hands a wallet absolute URLs built from the issuer id. Stripping the
        // issuer prefix must leave a path this spec declares — otherwise we publish, to third
        // parties, a URL our own contract does not contain.
        listOf("credential_endpoint", "token_endpoint").forEach { key ->
            val advertised = (body[key] as String).removePrefix(issuer)
            assertThat(declaredPaths)
                .describedAs("%s advertises '%s', which %s does not declare", key, advertised, SPEC_PATH)
                .contains(advertised)
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    /** `(METHOD, path)` pairs the spec declares, each resolved against the `servers:` base path. */
    private fun declaredOperations(): Set<String> = spec.at("/paths").properties()
        .flatMap { (rawPath, pathItem) ->
            pathItem.fieldNames().asSequence()
                .map { it.uppercase() }
                .filter { it in HTTP_METHODS }
                .map { method -> "$method ${normalise(serverBasePath() + rawPath)}" }
                .toList()
        }.toSet()

    /**
     * The path component of `servers[0].url` — empty for pid's absolute `http://localhost:8128`, and
     * `/api/v1` for a spec that declares a relative base. Extracted with [URI], never concatenated
     * whole: a spec whose paths look wrong is usually a spec whose server base was never applied.
     */
    private fun serverBasePath(): String {
        val url = spec.at("/servers/0/url").let { if (it.isMissingNode) return "" else it.asText() }
        return (if (url.startsWith("http")) URI(url).path.orEmpty() else url).trimEnd('/')
    }

    /**
     * `(METHOD, path)` pairs the running service really serves, discovered from the CDI container:
     * every JAX-RS resource in Quarkus is a bean, so this cannot miss one the way a list maintained
     * in this file would — it would drift exactly the way the spec does.
     *
     * Scoped to this service's own package. The `com.openbank.libs.*` resources every service
     * inherits from the shared runtime (`/api/v1/info`, `/api/v1/config`, `/q/openbank/sbom`,
     * `/q/openbank/docs`) are fleet platform surface that no service's `openapi.yaml` declares.
     */
    private fun servedOperations(): Set<String> =
        Arc.container().beanManager().getBeans(kotlin.Any::class.java, CdiAny.Literal.INSTANCE)
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

    /** JSON-Pointer-escape a path so it can index into `/paths`. */
    private fun pointer(path: String): String = path.replace("~", "~0").replace("/", "~1")

    private fun responseSchema(path: String, method: String, status: String): JsonNode = deref(
        spec.at("/paths/${pointer(path)}/$method/responses/$status/content/application~1json/schema"),
    )

    private fun requestSchema(path: String, method: String): JsonNode = deref(
        spec.at("/paths/${pointer(path)}/$method/requestBody/content/application~1json/schema"),
    )

    /** Follow a local `$ref` — pid's schemas live in `components`, so nothing resolves without this. */
    private fun deref(node: JsonNode): JsonNode {
        val ref = node.path("\$ref").asText("")
        if (ref.isEmpty()) return node
        val resolved = spec.at(ref.removePrefix("#"))
        assertThat(resolved.isMissingNode).describedAs("dangling \$ref '%s' in %s", ref, SPEC_PATH).isFalse()
        return resolved
    }

    /**
     * A no-RČ namesake query against the empty Flyway schema: the tier-2 match-key path, which
     * `PidBootSmokeIT` established resolves to `NO_MATCH` without any seeding.
     */
    private fun resolveBody(): Map<String, kotlin.Any> = given()
        .contentType(ContentType.JSON)
        .body("""{"givenName":"Contract","familyName":"Conformance","birthdate":"1990-01-01"}""")
        .post(RESOLVE_PATH)
        .then().statusCode(200)
        .extract().body().jsonPath().getMap("")

    private companion object {
        const val SPEC_PATH = "src/main/resources/openapi.yaml"
        const val SERVICE_PACKAGE = "com.openbank.pid."
        const val RESOLVE_PATH = "/api/v1/parties/resolve"
        const val WELL_KNOWN_PATH = "/.well-known/openid-credential-issuer"
        val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
    }
}
