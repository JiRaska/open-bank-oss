// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.document.infrastructure.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.document.domain.event.DocumentGenerated
import com.openbank.document.domain.event.SignatureCeremonyCompleted
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class DocumentEventSchemaValidatorTest {
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val registry = SimpleMeterRegistry()
    private val validator = DocumentEventSchemaValidator(mapper, registry, "observe")
    private val now = Instant.parse("2026-10-06T12:00:00Z")

    @Test
    fun `packaged schema equals the single reviewed contract file`() {
        val packaged = javaClass.getResource(DocumentEventSchemaValidator.SCHEMA_RESOURCE)!!.readText()
        val committed = File(
            "../openbank-contracts/openbank-document-service/schema/document-event.schema.json",
        ).readText()
        assertThat(packaged).isEqualTo(committed)
    }

    @Test
    fun `actual serialized bodies validate under their ce-type header`() {
        val documentId = UUID.randomUUID()
        val generated = mapper.writeValueAsString(
            DocumentGenerated(documentId, "statement", "1", "abc", now),
        )
        val ceremony = mapper.writeValueAsString(
            SignatureCeremonyCompleted(UUID.randomUUID(), documentId, now),
        )

        assertThat(validator.validate("document.generated.v1", generated)).isEmpty()
        assertThat(validator.validate("signature-ceremony.completed.v1", ceremony)).isEmpty()
        assertThat(validator.validate("signature-ceremony.completed.v1", generated)).isNotEmpty()
        assertThat(validator.validate("document.generated.v1", ceremony)).isNotEmpty()
    }

    @Test
    fun `observe mode reports drift without blocking the producer`() {
        validator.check("document.generated.v1", """{"documentId":"not-a-uuid"}""")
        validator.check("unexpected.v1", "{}")
        validator.check("document.generated.v1", "invalid-json")

        val invalidCount = registry.get("openbank_event_schema_validation_total")
            .tag("result", "invalid")
            .counter()
            .count()
        assertThat(invalidCount).isEqualTo(3.0)
    }

    @Test
    fun `enforce mode rejects drift before outbox persistence`() {
        val enforcing = DocumentEventSchemaValidator(mapper, registry, "enforce")
        assertThatThrownBy { enforcing.check("document.generated.v1", "{}") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("committed schema")
        assertThatThrownBy { enforcing.check("unexpected.v1", "{}") }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
