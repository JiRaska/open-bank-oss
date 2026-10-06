// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.document.infrastructure.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Checks the serialized outbox body and its event-type discriminator against the committed
 * ADR-0260 document pilot contract. The build packages that file verbatim from openbank-contracts.
 * Observe is the default until the live registry and consumer rollout have been verified (#1916).
 */
@ApplicationScoped
class DocumentEventSchemaValidator(
    private val mapper: ObjectMapper,
    private val metrics: MeterRegistry,
    @ConfigProperty(name = "openbank.event-schema.document-validation-mode", defaultValue = "observe")
    mode: String,
) {
    private val enforce = when (mode.lowercase()) {
        "observe" -> false
        "enforce" -> true
        else -> error("Unsupported document event schema validation mode: $mode")
    }

    private val schemas: Map<String, JsonSchema> = run {
        val stream = javaClass.getResourceAsStream(SCHEMA_RESOURCE)
            ?: error("Missing packaged document event schema: $SCHEMA_RESOURCE")
        val document = stream.use(mapper::readTree)
        val branches = document.path("oneOf")
        check(branches.isArray && branches.size() > 0) { "Document event schema has no oneOf branches" }
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val config = SchemaValidatorsConfig.builder()
            .typeLoose(false)
            .failFast(false)
            .formatAssertionsEnabled(true)
            .build()
        branches.associate { branch ->
            val eventType = branch.path("x-openbank-event-type").asText()
            check(eventType.isNotBlank()) { "Document event schema branch has no ce-type declaration" }
            // The x-* value is a header declaration, not a JSON Schema validation keyword.
            val payloadSchema = branch.deepCopy() as ObjectNode
            payloadSchema.remove("x-openbank-event-type")
            eventType to factory.getSchema(payloadSchema, config)
        }.also { check(it.size == branches.size()) { "Document event schema has duplicate ce-type declarations" } }
    }

    /** Called before the outbox row is inserted, inside the producer's business transaction. */
    fun check(eventType: String, payload: String) {
        val problems = validate(eventType, payload)
        val result = if (problems.isEmpty()) "valid" else "invalid"
        metrics.counter("openbank_event_schema_validation_total", "topic", TOPIC, "result", result).increment()
        if (problems.isNotEmpty()) {
            // Never log payloads: document events can contain customer data as schemas evolve.
            LOG.warnf("Document event schema mismatch: ce-type=%s, reasons=%s", eventType, problems.joinToString(","))
            if (enforce) error("Document event does not match the committed schema: ${problems.joinToString(",")}")
        }
    }

    internal fun validate(eventType: String, payload: String): List<String> {
        val schema = schemas[eventType] ?: return listOf("unregistered-event-type")
        val instance = try {
            mapper.readTree(payload)
        } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
            return listOf("invalid-json")
        } ?: return listOf("empty-json")
        return schema.validate(instance)
            .sortedWith(compareBy({ it.instanceLocation.toString() }, { it.code }))
            .take(MAX_REASONS)
            .map { "${it.code}@${it.instanceLocation}" }
    }

    companion object {
        const val SCHEMA_RESOURCE = "/event-schemas/document-event.schema.json"
        private const val TOPIC = "openbank.documents.document.event"
        private const val MAX_REASONS = 10
        private val LOG = Logger.getLogger(DocumentEventSchemaValidator::class.java)
    }
}
