// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.pension.application.annuity.AnnuityApplication
import com.openbank.pension.application.annuity.AnnuityQuoteRequest
import com.openbank.pension.application.annuity.PartnerCancellationReason
import com.openbank.pension.application.annuity.PartnerPolicyState
import com.openbank.pension.domain.annuity.AnnuityProviderTerms
import com.openbank.pension.domain.annuity.AnnuityType
import com.openbank.pension.domain.annuity.ApprovedPartner
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CONTRACT test of the reference annuity-partner protocol (#12383). The partner side is a server
 * driven ONLY by `annuity-partner-protocol-v1.yaml`: it answers documented operations with the
 * documented example, refuses undocumented ones, and validates every request body against the
 * documented schema. So the adapter and the published document cannot drift apart: an adapter
 * sending a field the document does not define, omitting a required one, or calling a path the
 * document does not have, turns this red; so does an example the adapter cannot parse.
 */
class ReferenceAnnuityProtocolContractTest {

    private val spec: Map<String, Any?> = javaClass.classLoader.getResourceAsStream("annuity-partner-protocol-v1.yaml")
        .use { Yaml().load(it) }
    private val json = jacksonObjectMapper()
    private val violations = CopyOnWriteArrayList<String>()
    private val calls = CopyOnWriteArrayList<String>()
    private lateinit var server: HttpServer
    private lateinit var provider: ApprovedPartner

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val method = exchange.requestMethod.lowercase()
            val path = exchange.requestURI.path
            calls += "$method $path"
            val (template, operation) = operation(method, path) ?: run {
                violations += "undocumented operation $method $path"
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestHeaders.getFirst("Authorization")?.startsWith("Bearer ") != true) {
                violations += "$template: no bearer token"
            }
            requiredHeaders(operation).filter { exchange.requestHeaders.getFirst(it).isNullOrBlank() }
                .forEach { violations += "$template: missing header $it" }
            val body = exchange.requestBody.readAllBytes().decodeToString()
            requestSchema(operation)?.let { schema ->
                validate(json.readValue(body, Any::class.java), schema, "$template body")
            }
            val example = json.writeValueAsBytes(okExample(operation))
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, example.size.toLong())
            exchange.responseBody.use { it.write(example) }
        }
        server.start()
        provider = ApprovedPartner("ref-partner", 1, terms("http://127.0.0.1:${server.address.port}"))
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val adapter = ReferenceRestAnnuityAdapter.create({ "partner-token" })

    @Test
    fun `quote - the request validates against the protocol and every documented example offer is parsed`(): Unit =
        runBlocking {
            val offers = adapter.quote(
                provider,
                AnnuityQuoteRequest(
                    requestId = UUID.randomUUID().toString(), premium = BigDecimal("1000000.00"), currency = "CZK",
                    jurisdiction = "CZ",
                    birthDate = LocalDate.parse(
                        "1965-01-01",
                    ),
                    startDate = LocalDate.parse("2030-02-01"),
                    types = setOf(AnnuityType.LIFELONG, AnnuityType.FIXED_TERM, AnnuityType.JOINT_LIFE),
                    guaranteeMonths = 60,
                    jointLifeBirthDate = LocalDate.parse(
                        "1967-01-01",
                    ),
                    survivorShare = BigDecimal("0.6"),
                ),
            )
            assertThat(violations).isEmpty()
            val documented = (
                okExample(
                    operation("post", "/annuity/v1/quotes")!!.second,
                ) as Map<*, *>
                )["offers"] as List<*>
            assertThat(offers).hasSize(documented.size)
            assertThat(offers.map { it.partnerId }.toSet()).containsExactly("ref-partner")
            assertThat(offers.first { it.type == AnnuityType.FIXED_TERM }.termMonths).isEqualTo(120)
            assertThat(offers.first().illustrative).isFalse()
        }

    @Test
    fun `purchase, status and cancellation follow the documented operations and carry the idempotency key`(): Unit =
        runBlocking {
            val applied = adapter.purchase(
                provider,
                AnnuityApplication(
                    requestId = "r1",
                    offerId = "OFR-1001",
                    premium = BigDecimal("1000000.00"),
                    currency = "CZK",
                    holderReference = UUID.randomUUID().toString(),
                    birthDate = LocalDate.parse("1965-01-01"),
                    premiumReference = "PENSION ANNUITY r1",
                    idempotencyKey = "k-apply",
                ),
            )
            assertThat(applied.state).isEqualTo(PartnerPolicyState.APPLIED)
            val status = adapter.status(provider, applied.applicationRef)
            assertThat(status.state).isEqualTo(PartnerPolicyState.ACTIVE)
            assertThat(status.policyRef).isEqualTo("POL-5501")
            val cancelled = adapter.cancel(
                provider,
                applied.applicationRef,
                PartnerCancellationReason.COOLING_OFF,
                "k-cancel",
            )
            assertThat(cancelled.state).isEqualTo(PartnerPolicyState.CANCELLED)
            assertThat(cancelled.refundRef).isNotNull()
            assertThat(violations).isEmpty()
            assertThat(calls).containsExactly(
                "post /annuity/v1/policies",
                "get /annuity/v1/policies/APP-77",
                "post /annuity/v1/policies/APP-77/cancellation",
            )
        }

    @Test
    fun `fails closed - no credential configured means no call at all`() {
        val unconfigured = ReferenceRestAnnuityAdapter.create({ null })
        assertThatThrownBy { runBlocking { unconfigured.status(provider, "APP-77") } }
            .hasMessageContaining("no credential")
        assertThat(calls).isEmpty()
    }

    @Test
    fun `known-negative - the validator itself rejects a body missing a required field or adding an unknown one`() {
        val schema = schemaRef("#/components/schemas/PolicyApplication")
        validate(mapOf("requestId" to "r", "extra" to 1), schema, "probe")
        assertThat(violations).anyMatch { it.contains("missing required 'offerId'") }
        assertThat(violations).anyMatch { it.contains("unknown property 'extra'") }
    }

    // ---- a minimal validator for the subset of OpenAPI the protocol uses ----

    @Suppress("UNCHECKED_CAST")
    private fun operation(method: String, path: String): Pair<String, Map<String, Any?>>? {
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        return paths.entries.firstNotNullOfOrNull { (template, ops) ->
            val regex = Regex("^" + template.replace(Regex("\\{[^/]+}"), "[^/]+") + "$")
            (ops[method] as? Map<String, Any?>)?.takeIf { regex.matches(path) }?.let { template to it }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun requiredHeaders(op: Map<String, Any?>): List<String> =
        (op["parameters"] as? List<Map<String, Any?>>).orEmpty().map { resolve(it) }
            .filter { it["in"] == "header" && it["required"] == true }.map { it["name"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun requestSchema(op: Map<String, Any?>): Map<String, Any?>? = (
        (
            ((op["requestBody"] as? Map<String, Any?>)?.get("content") as? Map<String, Any?>)
                ?.get("application/json") as? Map<String, Any?>
            )?.get("schema") as? Map<String, Any?>
        )?.let(::resolve)

    @Suppress("UNCHECKED_CAST")
    private fun okExample(op: Map<String, Any?>): Any? = (
        (((op["responses"] as Map<String, Any?>)["200"] as Map<String, Any?>)["content"] as Map<String, Any?>)
            ["application/json"] as Map<String, Any?>
        )["example"]

    @Suppress("UNCHECKED_CAST")
    private fun schemaRef(ref: String): Map<String, Any?> =
        ref.removePrefix("#/").split("/").fold<String, Any?>(spec) { node, key ->
            (node as Map<String, Any?>)[key]
        } as Map<String, Any?>

    private fun resolve(node: Map<String, Any?>): Map<String, Any?> =
        (node["\$ref"] as? String)?.let { resolve(schemaRef(it)) } ?: node

    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod")
    private fun validate(value: Any?, rawSchema: Map<String, Any?>, at: String) {
        val schema = resolve(rawSchema)
        (schema["enum"] as? List<Any?>)?.let { if (value !in it) violations += "$at: '$value' not in $it" }
        when (schema["type"]) {
            "object" -> {
                val obj = value as? Map<String, Any?> ?: return run { violations += "$at: not an object" }
                val props = (schema["properties"] as? Map<String, Map<String, Any?>>).orEmpty()
                (schema["required"] as? List<String>).orEmpty().filter { it !in obj }
                    .forEach { violations += "$at: missing required '$it'" }
                if (schema["additionalProperties"] == false) {
                    obj.keys.filter { it !in props }.forEach { violations += "$at: unknown property '$it'" }
                }
                obj.forEach { (k, v) -> props[k]?.let { validate(v, it, "$at.$k") } }
            }
            "array" -> (value as? List<*>)?.forEach { validate(it, schema["items"] as Map<String, Any?>, "$at[]") }
                ?: run { violations += "$at: not an array" }
            "string" -> if (value !is String) violations += "$at: not a string"
            "number" -> if (value !is Number) violations += "$at: not a number"
            "integer" -> if (value !is Int && value !is Long) violations += "$at: not an integer"
            "boolean" -> if (value !is Boolean) violations += "$at: not a boolean"
        }
    }

    private fun terms(endpoint: String) = AnnuityProviderTerms(
        legalName = "Reference Life", legalEntityPartyId = UUID.randomUUID(), licenceRef = "L",
        licenceAuthority = "CNB",
        jurisdictions = setOf("CZ"), supportedTypes = AnnuityType.entries.toSet(), currency = "CZK",
        minPremium = BigDecimal.ONE, maxPremium = BigDecimal("100000000"), coolingOffDays = 30,
        premiumIban = "CZ5508000000001234567899", adapter = "reference-rest", endpointUrl = endpoint,
        effectiveFrom = LocalDate.parse("2020-01-01"),
    )
}
