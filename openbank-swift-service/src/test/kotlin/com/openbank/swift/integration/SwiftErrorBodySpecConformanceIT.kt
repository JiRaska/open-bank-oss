// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import com.openbank.swift.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.util.UUID

/**
 * #11972: the error bodies this service ACTUALLY sends, checked against the schema its
 * `openapi.yaml` publishes for them.
 *
 * The spec used to declare every error as `ErrorResponse { error, message }` — `error` required —
 * and libs-runtime has never sent an `error` member: a body is either its `ApiError` or the
 * ADR-0326 problem document (a superset of it). `oasdiff` compares a spec only to its previous
 * revision, so nothing could notice. This test asks the running service instead: every response
 * below is a real one, and each must validate against `components.schemas.ProblemDetail` as
 * written in the spec — required members present, no undeclared member, declared types honoured.
 * Change the spec back to `ErrorResponse` and every case here fails on the missing `error`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class SwiftErrorBodySpecConformanceIT {

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
        const val REF_LEN = 12

        @Suppress("UNCHECKED_CAST")
        val SCHEMAS: Map<String, Map<String, Any?>> by lazy {
            val spec = Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!
                .use { Yaml().load<Map<String, Any?>>(it) }
            (spec["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>
        }
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `400 for an amount Money cannot hold is a ProblemDetail`() {
        val body = post("/api/v1/swift", sendBody(amount = "150.5"), 400)
        assertConforms(body, "ProblemDetail")
        assertThat(body["code"]).isEqualTo("AMOUNT_SCALE_EXCEEDED")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `400 for a null body is a ProblemDetail`() {
        assertConforms(post("/api/v1/swift", "null", 400), "ProblemDetail")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `400 for a failed BIC validation is a ProblemDetail`() {
        assertConforms(post("/api/v1/swift", sendBody(senderBic = "SHORT"), 400), "ProblemDetail")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_VIEWER"])
    fun `404 for an unknown message is a ProblemDetail`() {
        val body: Map<String, Any?> = When {
            get("/api/v1/swift/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        } Extract {
            jsonPath().getMap("")
        }
        assertConforms(body, "ProblemDetail")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `error for acknowledging an unknown message is a ProblemDetail`() {
        val body: Map<String, Any?> = Given {
            contentType("application/json")
            body("""{"ackRef":"x"}""")
        } When {
            post("/api/v1/swift/${UUID.randomUUID()}/ack")
        } Then {
            statusCode(422)
        } Extract {
            jsonPath().getMap("")
        }
        assertConforms(body, "ProblemDetail")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_VIEWER"])
    fun `403 from a role check carries no body at all`() {
        // @RolesAllowed is refused by Quarkus security before any libs-runtime mapper runs, so this
        // 403 is EMPTY. Only an OPA deny (PolicyDeniedException) carries a ProblemDetail body; the
        // spec says so on its Forbidden response rather than promising a body every 403 lacks.
        val raw = Given {
            contentType("application/json")
            body(sendBody())
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(403)
        } Extract {
            asString()
        }
        assertThat(raw).isEmpty()
    }

    @Test
    fun `the published schema still rejects the shape it used to describe`() {
        // Falsification: the validator below must be able to say no. A body in the OLD documented
        // shape lacks every required ProblemDetail member and carries an undeclared `error`.
        val old = mapOf("error" to "NOT_FOUND", "message" to "x", "traceId" to "t")
        assertThat(violations(old, "ProblemDetail")).isNotEmpty()
    }

    private fun post(path: String, json: String, status: Int): Map<String, Any?> = Given {
        contentType("application/json")
        body(json)
    } When {
        post(path)
    } Then {
        statusCode(status)
    } Extract {
        jsonPath().getMap("")
    }

    private fun assertConforms(body: Map<String, Any?>, schema: String) {
        assertThat(violations(body, schema)).describedAs("body %s against %s", body, schema).isEmpty()
    }

    /** A deliberately small JSON-Schema subset: required, additionalProperties=false, type, items, $ref. */
    @Suppress("UNCHECKED_CAST")
    private fun violations(value: Any?, schemaName: String, path: String = "$"): List<String> {
        val schema = SCHEMAS.getValue(schemaName)
        val obj = value as? Map<String, Any?> ?: return listOf("$path: not an object")
        val props = schema["properties"] as Map<String, Map<String, Any?>>
        val out = mutableListOf<String>()
        (schema["required"] as? List<String> ?: emptyList()).filterNot(obj::containsKey)
            .forEach { out += "$path.$it: required, absent" }
        if (schema["additionalProperties"] == false) {
            (obj.keys - props.keys).forEach { out += "$path.$it: not declared" }
        }
        obj.forEach { (k, v) -> props[k]?.let { out += typeViolations(v, it, "$path.$k") } }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun typeViolations(v: Any?, prop: Map<String, Any?>, path: String): List<String> {
        (prop["\$ref"] as? String)?.let { return violations(v, it.substringAfterLast('/'), path) }
        val ok = when (prop["type"]) {
            null -> true
            "string" -> v is String
            "integer" -> v is Int || v is Long
            "boolean" -> v is Boolean
            "array" -> v is List<*>
            "object" -> v is Map<*, *>
            else -> false
        }
        if (!ok) return listOf("$path: expected ${prop["type"]}, got $v")
        val items = prop["items"] as? Map<String, Any?> ?: return emptyList()
        return (v as List<*>).flatMapIndexed { i, e -> typeViolations(e, items, "$path[$i]") }
    }

    private fun sendBody(amount: String = "150000", senderBic: String = "OPBKCZPP") =
        """
        {
          "idempotencyKey": "swift-err-${UUID.randomUUID()}",
          "messageType": "MT103",
          "senderBic": "$senderBic",
          "receiverBic": "DEUTDEFF",
          "transactionReference": "${UUID.randomUUID().toString().take(REF_LEN)}",
          "relatedReference": null,
          "valueDate": "20260120",
          "currency": "EUR",
          "amountMinorUnits": $amount,
          "orderingCustomerAccount": "DE89370400440532013000",
          "orderingCustomerAccountId": null,
          "orderingCustomerName": "Alice",
          "beneficiaryAccount": "GB33BUKB20201555555555",
          "beneficiaryName": "Bob",
          "remittanceInfo": "error body conformance",
          "chargeCode": "SHA",
          "priority": "NORMAL"
        }
        """.trimIndent()
}
