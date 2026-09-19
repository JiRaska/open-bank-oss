// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * These events are serialised data classes, so the wire key exists only at runtime as a Kotlin
 * property name — a grep for `"occurredAt"` over this module finds nothing while every payload
 * carries it (#3883). The keys are therefore asserted on real serialised JSON.
 *
 * `occurredAt` (not `at`) is the fleet's single accepted spelling for a domain event's business
 * time and the only one audit-service's AuditConsumer reads; while these events spelled it `at`,
 * every audit row recorded the CONSUMER's ingest clock as the business time (#3907/#3914).
 */
class DocumentEventsTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val at: Instant = Instant.parse("2026-01-02T03:04:05Z")

    private fun keysOf(event: Any): Set<String> =
        mapper.readValue(mapper.writeValueAsString(event), Map::class.java).keys.map { it.toString() }.toSet()

    @Test
    fun `every event serialises its business time as occurredAt, never as at`() {
        val events = listOf(
            DocumentTemplatePublished(UUID.randomUUID(), "VOP", "1.0.0", at),
            DocumentGenerated(UUID.randomUUID(), "VOP", "1.0.0", "e".repeat(64), at),
            DocumentSigned(UUID.randomUUID(), UUID.randomUUID(), at),
            SignatureCeremonyCompleted(UUID.randomUUID(), UUID.randomUUID(), at),
        )

        events.forEach { event ->
            assertThat(keysOf(event)).contains("occurredAt").doesNotContain("at")
        }
    }

    @Test
    fun `every event declares document-service as its producer, so audit attribution is EVENT-sourced`() {
        assertThat(DocumentGenerated(UUID.randomUUID(), "VOP", "1.0.0", "f".repeat(64), at).sourceService)
            .isEqualTo("document-service")
        assertThat(SignatureCeremonyCompleted(UUID.randomUUID(), UUID.randomUUID(), at).sourceService)
            .isEqualTo("document-service")
        assertThat(DocumentSigned(UUID.randomUUID(), UUID.randomUUID(), at).sourceService)
            .isEqualTo("document-service")
        assertThat(DocumentTemplatePublished(UUID.randomUUID(), "VOP", "1.0.0", at).sourceService)
            .isEqualTo("document-service")
    }

    @Test
    fun `the business time is carried through serialisation unchanged, not re-stamped`() {
        val event = DocumentSigned(UUID.randomUUID(), UUID.randomUUID(), at)

        val back = mapper.readValue(mapper.writeValueAsString(event), DocumentSigned::class.java)

        // The producer's own clock, to the instant — not the moment the payload was written.
        assertThat(back.occurredAt).isEqualTo(at)
        assertThat(back).isEqualTo(event)
    }
}
