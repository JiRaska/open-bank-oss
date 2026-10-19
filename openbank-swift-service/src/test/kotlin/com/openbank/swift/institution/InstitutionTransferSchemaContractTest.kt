// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.institution

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.domain.institution.InstitutionTransferStatus
import com.openbank.swift.domain.institution.InstitutionTransferStatusChanged
import com.openbank.swift.domain.institution.ReleaseValidation
import com.openbank.swift.domain.institution.ReleasedPaymentValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Contracts 9.1 and 9.2 as committed JSON Schemas under `openbank-contracts/openbank-swift-service/schema/`,
 * held against the code both ways: what this service PRODUCES (9.2) must conform to its schema,
 * and every release the rail ACCEPTS (9.1) must conform to the schema treasury's producer tests can
 * replay. A small structural checker (required, type, enum, pattern, nested objects), enough for
 * these flat documents, so a schema edit or a payload edit that drifts apart turns this red.
 */
class InstitutionTransferSchemaContractTest {
    private val mapper = ObjectMapper()
    private val dir = File("../openbank-contracts/openbank-swift-service/schema")
    private val statusSchema = mapper.readTree(File(dir, "institution-transfer-status.schema.json"))
    private val releaseSchema = mapper.readTree(File(dir, "treasury-payment-released.schema.json"))

    @Test
    fun `every status event the rail can emit conforms to the 9_2 schema`() {
        val announced = InstitutionTransferStatus.entries -
            setOf(InstitutionTransferStatus.SUBMITTING, InstitutionTransferStatus.SUBMISSION_UNKNOWN)
        for (status in announced) {
            for (ssi in listOf(null, 3L)) {
                val event = InstitutionTransferStatusChanged(
                    paymentId = "1b4e28ba-2fa1-4d2e-883f-0016d3cca427",
                    idempotencyKey = "treasury:a:b:payment",
                    status = status.name,
                    messageType = "pacs.009",
                    schemeReference = if (status == InstitutionTransferStatus.REJECTED) null else "uetr",
                    reasonCode = if (status == InstitutionTransferStatus.REJECTED) "AC04" else null,
                    reason = null,
                    at = "2026-10-19T08:00:00Z",
                    synthetic = false,
                    ssiVersion = ssi,
                )
                assertThat(
                    violations(statusSchema, mapper.valueToTree(event), releaseSchema),
                ).describedAs("%s", event).isEmpty()
            }
        }
    }

    @Test
    fun `the 9_2 schema declares exactly the fields the payload class carries`() {
        val declared = statusSchema.path("properties").fieldNames().asSequence().toSet()
        val produced = mapper.valueToTree<JsonNode>(
            InstitutionTransferStatusChanged("p", null, "SENT", "pacs.009", null, null, null, "t", false),
        ).fieldNames().asSequence().toSet()
        assertThat(declared).isEqualTo(produced)
    }

    @Test
    fun `releases the rail accepts conform to the 9_1 schema and the schema catches a malformed one`() {
        for (rail in listOf("CERTIS", "TARGET2", "SWIFT")) {
            val m = InstitutionTransferFixtures.release(rail, extra = mapOf("ssiVersion" to 2))
            assertThat(
                ReleasedPaymentValidator.validate(
                    InstitutionTransferFixtures.parsed(m),
                    InstitutionTransferFixtures.TODAY,
                    1,
                ),
            )
                .isInstanceOf(ReleaseValidation.Valid::class.java)
            assertThat(
                violations(releaseSchema, mapper.readTree(InstitutionTransferFixtures.json(m)), releaseSchema),
            ).isEmpty()
        }
        // Negative controls: the checker must be able to fail.
        val bad = InstitutionTransferFixtures.release(amount = "1.5", drop = setOf("releasedBy"))
        assertThat(violations(releaseSchema, mapper.readTree(InstitutionTransferFixtures.json(bad)), releaseSchema))
            .anyMatch { it.contains("amount") }.anyMatch { it.contains("releasedBy") }
        val badStatus = mapper.createObjectNode().put("paymentId", "x").put("status", "DONE")
        assertThat(violations(statusSchema, badStatus, statusSchema)).anyMatch {
            it.contains("status")
        }.anyMatch { it.contains("at") }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun violations(schema: JsonNode, value: JsonNode, root: JsonNode, path: String = "$"): List<String> {
        val s = if (schema.has("\$ref")) root.at(schema.path("\$ref").asText().removePrefix("#")) else schema
        if (s.has("oneOf")) {
            return if (s.path("oneOf").any {
                    violations(it, value, root, path).isEmpty()
                }
            ) {
                emptyList()
            } else {
                listOf("$path matches no oneOf branch")
            }
        }
        val out = mutableListOf<String>()
        val types = s.path("type").let { t ->
            if (t.isArray) {
                t.map { it.asText() }
            } else if (t.isTextual) {
                listOf(t.asText())
            } else {
                emptyList()
            }
        }
        if (types.isNotEmpty() &&
            types.none { typeMatches(it, value) }
        ) {
            return listOf("$path is not ${types.joinToString("|")}")
        }
        if (value.isNull) return out
        s.path("enum").takeIf { it.isArray }?.let { e -> if (e.none { it == value }) out += "$path not in enum" }
        s.path("pattern").takeIf { it.isTextual && value.isTextual }?.let {
            if (!Regex(it.asText()).matches(value.asText())) {
                out +=
                    "$path fails pattern"
            }
        }
        s.path("maxLength").takeIf { it.isInt && value.isTextual }?.let {
            if (value.asText().length >
                it.asInt()
            ) {
                out += "$path too long"
            }
        }
        s.path("minimum").takeIf { it.isNumber && value.isNumber }?.let {
            if (value.asLong() <
                it.asLong()
            ) {
                out += "$path below minimum"
            }
        }
        if (value.isObject) {
            s.path("required").forEach { if (!value.has(it.asText())) out += "$path.${it.asText()} is required" }
            s.path("properties").fields().forEach { (k, sub) ->
                if (value.has(k)) {
                    out +=
                        violations(sub, value.get(k), root, "$path.$k")
                }
            }
        }
        if (value.isArray) {
            s.path("items").takeIf { !it.isMissingNode }?.let { item ->
                value.forEachIndexed { i, v ->
                    out +=
                        violations(item, v, root, "$path[$i]")
                }
            }
        }
        return out
    }

    private fun typeMatches(type: String, v: JsonNode): Boolean = when (type) {
        "string" -> v.isTextual
        "integer" -> v.isIntegralNumber
        "number" -> v.isNumber
        "boolean" -> v.isBoolean
        "object" -> v.isObject
        "array" -> v.isArray
        "null" -> v.isNull
        else -> false
    }
}
