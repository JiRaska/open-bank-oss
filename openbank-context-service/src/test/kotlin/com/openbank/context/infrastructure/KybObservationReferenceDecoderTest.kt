// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class KybObservationReferenceDecoderTest {
    private val decoder = KybObservationReferenceDecoder(ObjectMapper())
    private val eventId = UUID.randomUUID().toString()
    private val caseId = UUID.randomUUID().toString()
    private val observationId = UUID.randomUUID().toString()
    private val payload = """{"schemaVersion":1,"eventType":"KybUboObservationRecorded", """ +
        """"caseId":"$caseId","observationId":"$observationId","revision":1,"sourceSha256":"${"a".repeat(64)}"}"""

    @Test
    fun `accepts exact reference-only versioned contract`() {
        val result = decoder.decode(payload, eventId, KybObservationReferenceDecoder.EVENT_TYPE)
        assertThat(result.caseId).isEqualTo(UUID.fromString(caseId))
        assertThat(result.observationId).isEqualTo(UUID.fromString(observationId))
        assertThat(result.revision).isEqualTo(1)
    }

    @Test
    fun `accepts restriction only when payload and header types agree`() {
        val restricted = payload.replace("KybUboObservationRecorded", "KybUboObservationRestricted")
        val result = decoder.decode(restricted, eventId, KybObservationReferenceDecoder.RESTRICTED_EVENT_TYPE)
        assertThat(result.eventType).isEqualTo(KybObservationReferenceDecoder.RESTRICTED_EVENT_TYPE)
        assertThatThrownBy {
            decoder.decode(restricted, eventId, KybObservationReferenceDecoder.EVENT_TYPE)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects owner fields and schema drift rather than retaining personal data`() {
        assertThatThrownBy {
            decoder.decode(
                payload.dropLast(1) + ",\"fullName\":\"Example Owner\"}",
                eventId,
                KybObservationReferenceDecoder.EVENT_TYPE,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            decoder.decode(
                payload.replace("schemaVersion\":1", "schemaVersion\":2"),
                eventId,
                KybObservationReferenceDecoder.EVENT_TYPE,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            decoder.decode(payload, eventId, "BusinessOnboardingStarted")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects invalid hash revision and identifiers`() {
        assertThatThrownBy {
            decoder.decode(
                payload.replace("a".repeat(64), "not-a-hash"),
                eventId,
                KybObservationReferenceDecoder.EVENT_TYPE,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            decoder.decode(
                payload.replace("revision\":1", "revision\":0"),
                eventId,
                KybObservationReferenceDecoder.EVENT_TYPE,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            decoder.decode(payload, "not-an-event-id", KybObservationReferenceDecoder.EVENT_TYPE)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
