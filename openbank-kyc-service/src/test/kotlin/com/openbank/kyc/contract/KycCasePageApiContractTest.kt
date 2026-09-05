// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.openbank.kyc.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Spec-conformance contract test for the **paginated KYC case list** (`GET /api/v1/kyc/cases`),
 * issue #8163.
 *
 * ## Why this, and not a Pact
 *
 * The consumer of this envelope is `openbank-admin-ui` — a Next.js app. There is no Kotlin consumer
 * in this repo that calls this route, and a pact written against a fictional one pins nothing: the
 * Pact mock server answers whatever path the client asks for, so a consumer pact cannot catch a
 * wrong path, a dropped field, or a renamed one. Only the **provider** side can, and the counterparty
 * that actually exists here is the committed `openapi.yaml` — the document `#8164` published as
 * `1.8.0` and which every generated client and every integrator codes against. So the spec is the
 * contract, and this test replays it against the running service. It is a plain `@QuarkusTest`, so it
 * runs on every PR that touches this module — unlike a `@PactBroker` class, whose
 * `@EnabledIfSystemProperty(pactbroker.url)` gate is skipped in the PR lane.
 *
 * The spec is **parsed**, never grepped: every expectation below is derived by JSON-Pointer
 * navigation with `$ref` resolution, so no assertion can pass by matching prose. `#8164` changed only
 * the document (`git show --stat` — one file), which left the two halves free to drift with nothing
 * to notice. This is the thing that notices.
 *
 * ## What it can actually fail on
 *
 *  - a property dropped from, added to, or renamed on the wire (`statusFilter` -> `status`, say):
 *    the key sets stop matching in whichever direction the drift went;
 *  - a required property that the wire omits — including the case where Jackson's null-inclusion is
 *    ever flipped to `NON_NULL` fleet-wide, which would silently delete `statusFilter` from every
 *    unfiltered response while the spec still declares it required;
 *  - a value whose JSON type stops matching the declared one (`total` becoming a string);
 *  - a `status` value the spec's enum does not declare;
 *  - `page`/`size` no longer describing the window that was actually served, which is what a caller
 *    divides `total` by to size its pager.
 *
 * Falsified before it was trusted: with `"statusFilter" to status?.name` removed from
 * `KycResource.listCases`, `the list envelope emits every property the spec requires` fails; with the
 * pre-#8163 `"size" to size` restored, `page and size describe the window that was actually served`
 * fails on `?size=500`.
 */
@QuarkusTest
@QuarkusTestResource(KycCasePageApiContractTest.ContractHarness::class)
@QuarkusTestResource(PostgresTestResource::class)
class KycCasePageApiContractTest {

    /**
     * Same recipe as `KycOutboxWriteIT`: the outbox dispatcher off so it cannot contend with the
     * reads, and `authz.enforce` off because there is no OPA sidecar in a test JVM and the
     * interceptor correctly fails closed (503) without one. The subject here is the response
     * envelope, not the policy decision — `KycSecurityTest` owns that.
     */
    class ContractHarness : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "authz.enforce" to "false",
        )
        override fun stop() = Unit
    }

    private val spec: JsonNode = YAMLMapper().readTree(File(SPEC_PATH))
    private val json = ObjectMapper()

    // ------------------------------------------------------------------ the spec declares the route

    @Test
    fun `the spec declares the paginated list operation and its response schema resolves`() {
        val operation = spec.at("/paths/${pointer(CASES_PATH)}/get")
        assertThat(operation.isMissingNode)
            .describedAs("%s must declare GET %s", SPEC_PATH, CASES_PATH)
            .isFalse()

        val schema = pageSchema()
        assertThat(schema.at("/properties").isMissingNode)
            .describedAs("the 200 schema for GET %s must resolve to an object with properties", CASES_PATH)
            .isFalse()
        assertThat(schema.at("/required").map { it.asText() })
            .describedAs("declared required properties of the page envelope")
            .isNotEmpty()
    }

    // --------------------------------------------------------------------------- the envelope shape

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_ADMIN"])
    fun `the list envelope emits every property the spec requires, and declares every one it emits`() {
        openCase()
        val body = listCases()
        val declared = pageSchema().at("/properties").fieldNames().asSequence().toSet()
        val required = pageSchema().at("/required").map { it.asText() }
        val onTheWire = body.fieldNames().asSequence().toSet()

        assertThat(declared).describedAs("declared properties of KycCasePage").isNotEmpty()
        // Both directions at once. An undeclared property is one no generated client will carry; a
        // declared-but-absent required one is a client field that is always undefined.
        assertThat(onTheWire)
            .describedAs(
                "undeclared properties on GET %s — %s would not tell an integrator about them",
                CASES_PATH,
                SPEC_PATH,
            )
            .isSubsetOf(declared)
        assertThat(onTheWire)
            .describedAs("spec-required properties missing from the wire")
            .containsAll(required)
    }

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_ADMIN"])
    fun `every envelope property has a JSON type the spec declares`() {
        openCase()
        val body = listCases()
        val properties = pageSchema().at("/properties")

        properties.properties().forEach { (name, propertySchema) ->
            val actual = body.get(name) ?: return@forEach // absent — the test above owns that
            val declaredTypes = declaredTypes(propertySchema)
            assertThat(jsonTypeOf(actual).any { it in declaredTypes })
                .describedAs("'%s' is declared %s but the wire value was %s", name, declaredTypes, actual)
                .isTrue()
        }
    }

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_ADMIN"])
    fun `an unfiltered list carries statusFilter explicitly as null, not by omission`() {
        openCase()
        val body = listCases()

        // `statusFilter` is a REQUIRED property whose declared type includes 'null'. Present-and-null
        // and absent are different bytes on the wire and different values to a typed client, and only
        // a parsed body can tell them apart — a map view collapses both to "no value".
        assertThat(body.has("statusFilter"))
            .describedAs("GET %s with no ?status= must still carry the required 'statusFilter' key", CASES_PATH)
            .isTrue()
        assertThat(body.get("statusFilter").isNull)
            .describedAs("statusFilter with no ?status= filter applied")
            .isTrue()
        assertThat(declaredTypes(pageSchema().at("/properties/statusFilter")))
            .describedAs("the spec must declare statusFilter nullable for that to be legal")
            .contains("null")
    }

    // ------------------------------------------------------------------------------ negative auth

    @Test
    fun `rejects the case-page request with 401 when the caller has no valid identity`() {
        // No @TestSecurity identity at all: `@RolesAllowed` on `KycResource.listCases` must answer
        // 401 before the handler — and before this test's own contract assertions — ever run.
        // `authz.enforce=false` in ContractHarness only disables the advisory `@Authorize`
        // interceptor; it does not touch this outer RBAC gate (see KycSecurityTest).
        given()
            .get(CASES_PATH)
            .then()
            .statusCode(401)
    }

    // ------------------------------------------------------------------- the window that was served

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_ADMIN"])
    fun `page and size describe the window that was actually served, not the raw query`() {
        repeat(2) { openCase() }

        val firstPage = listCases("?page=0&size=1")
        assertThat(firstPage.get("page").asInt()).describedAs("echoed page").isEqualTo(0)
        assertThat(firstPage.get("size").asInt()).describedAs("echoed size").isEqualTo(1)
        assertThat(firstPage.get("items").size()).describedAs("items on a size=1 page").isLessThanOrEqualTo(1)
        assertThat(firstPage.get("total").asLong()).describedAs("total across all pages").isGreaterThanOrEqualTo(2)

        // An over-sized request is clamped for the query, so echoing the raw value would hand a
        // caller a page size the service never served. The bounds are the spec's own.
        val bounds = sizeParameterBounds()
        val clamped = listCases("?size=500")
        assertThat(clamped.get("size").asInt())
            .describedAs("echoed size for ?size=500 vs the bounds %s declares for the parameter", SPEC_PATH)
            .isBetween(bounds.first, bounds.second)
    }

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_ADMIN"])
    fun `a negative page answers the first page rather than failing`() {
        openCase()

        // Measured before the fix: this answered 200 and echoed `"page": -1`, so a pager reading its
        // own offset back out of the envelope pages forward from a negative one. Not a 500, which is
        // why nothing noticed.
        val body = listCases("?page=-1")
        assertThat(body.get("page").asInt()).describedAs("echoed page for ?page=-1").isEqualTo(0)
    }

    // ------------------------------------------------------------------------------ the status filter

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_ADMIN"])
    fun `the status filter is echoed with a value the spec declares and constrains the items`() {
        openCase()

        val declaredFilters = spec
            .at("/paths/${pointer(CASES_PATH)}/get/parameters")
            .single { it.path("name").asText() == "status" }
            .at("/schema/enum").map { it.asText() }
        assertThat(declaredFilters).describedAs("declared ?status= values").contains("OPEN")

        val body = listCases("?status=OPEN")
        assertThat(body.get("statusFilter").asText())
            .describedAs("echoed statusFilter vs the enum in %s", SPEC_PATH)
            .isIn(declaredFilters as Iterable<Any>)
        assertThat(body.get("items")).describedAs("a freshly opened case must appear under ?status=OPEN").isNotEmpty
        body.get("items").forEach {
            assertThat(it.get("status").asText())
                .describedAs("every item under ?status=OPEN")
                .isEqualTo("OPEN")
        }
    }

    @Test
    @TestSecurity(user = "contract-test", roles = ["ROLE_ADMIN"])
    fun `each listed case declares every property the item schema requires`() {
        openCase()

        val itemSchema = deref(pageSchema().at("/properties/items/items"))
        val declared = itemSchema.at("/properties").fieldNames().asSequence().toSet()
        val required = itemSchema.at("/required").map { it.asText() }
        val item = listCases().get("items").first()

        assertThat(item.fieldNames().asSequence().toSet())
            .describedAs("undeclared properties on a KycCaseResponse item")
            .isSubsetOf(declared)
        assertThat(item.fieldNames().asSequence().toSet())
            .describedAs("spec-required item properties missing from the wire")
            .containsAll(required)
    }

    // ------------------------------------------------------------------------------------- helpers

    private fun openCase(): UUID = UUID.fromString(
        given()
            .contentType(ContentType.JSON)
            .body("""{"partyId":"${UUID.randomUUID()}"}""")
            .post(CASES_PATH)
            .then().statusCode(201)
            .extract().body().jsonPath().getString("id"),
    )

    /**
     * The raw response body, parsed. Deliberately not RestAssured's map view: that collapses a
     * present-and-null property into the same shape as an absent one, which is the exact
     * distinction the `statusFilter` test above turns on.
     */
    private fun listCases(query: String = ""): JsonNode = json.readTree(
        given()
            .get("$CASES_PATH$query")
            .then().statusCode(200)
            .extract().body().asString(),
    )

    private fun pageSchema(): JsonNode = deref(
        spec.at("/paths/${pointer(CASES_PATH)}/get/responses/200/content/application~1json/schema"),
    )

    /** `(minimum, maximum)` the spec declares for the `size` query parameter. */
    private fun sizeParameterBounds(): Pair<Int, Int> {
        val schema = spec.at("/paths/${pointer(CASES_PATH)}/get/parameters")
            .single { it.path("name").asText() == "size" }
            .at("/schema")
        assertThat(schema.has("minimum") && schema.has("maximum"))
            .describedAs("%s must bound the size parameter for this assertion to mean anything", SPEC_PATH)
            .isTrue()
        return schema.get("minimum").asInt() to schema.get("maximum").asInt()
    }

    /**
     * The JSON Schema type name for a value as it actually arrived on the wire. `integer` is
     * deliberately reported as `number` too, and both spellings are accepted, because JSON itself
     * does not distinguish them — only the spec does, and a `total` of `2` is legal under either.
     */
    private fun jsonTypeOf(value: JsonNode): Set<String> = when {
        value.isNull -> setOf("null")
        value.isArray -> setOf("array")
        value.isObject -> setOf("object")
        value.isTextual -> setOf("string")
        value.isBoolean -> setOf("boolean")
        value.isNumber -> setOf("integer", "number")
        else -> emptySet()
    }

    /** OpenAPI 3.1 allows `type: string` and `type: [string, 'null']` — both are normalised here. */
    private fun declaredTypes(schema: JsonNode): Set<String> {
        val type = deref(schema).path("type")
        return when {
            type.isArray -> type.map { it.asText() }.toSet()
            type.isTextual -> setOf(type.asText())
            else -> emptySet()
        }
    }

    /** Follow a local `$ref` — the page envelope and its items both live under `components`. */
    private fun deref(node: JsonNode): JsonNode {
        val ref = node.path("\$ref").asText("")
        if (ref.isEmpty()) return node
        val resolved = spec.at(ref.removePrefix("#"))
        assertThat(resolved.isMissingNode).describedAs("dangling \$ref '%s' in %s", ref, SPEC_PATH).isFalse()
        return resolved
    }

    /** JSON-Pointer-escape a path so it can index into `/paths`. */
    private fun pointer(path: String): String = path.replace("~", "~0").replace("/", "~1")

    private companion object {
        const val SPEC_PATH = "src/main/resources/openapi.yaml"
        const val CASES_PATH = "/api/v1/kyc/cases"
    }
}
