// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FraudCaseReferenceDecoderTest {
    private val decoder = FraudCaseReferenceDecoder(ObjectMapper())

    @Test
    fun `accepts the exact four-field opened reference`() {
        val caseId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val reference = decoder.decode(body(caseId), eventId.toString(), FraudCaseReferenceDecoder.OPENED)
        assertThat(reference.caseId).isEqualTo(caseId)
        assertThat(reference.eventId).isEqualTo(eventId)
        assertThat(reference.revision).isEqualTo(1)
    }

    @Test
    fun `rejects extra sensitive fields and mismatched lifecycle metadata`() {
        val caseId = UUID.randomUUID()
        val eventId = UUID.randomUUID().toString()
        assertThatThrownBy {
            val expanded = body(caseId).dropLast(1) + ""","accountId":"${UUID.randomUUID()}"}"""
            decoder.decode(expanded, eventId, "fraud.case_opened")
        }.hasMessageContaining("unexpected fields")
        assertThatThrownBy {
            decoder.decode(body(caseId), eventId, "fraud.case_closed")
        }.hasMessageContaining("disagrees with header")
        assertThatThrownBy {
            decoder.decode(body(caseId).replace("\"revision\":1", "\"revision\":2"), eventId, "fraud.case_opened")
        }.hasMessageContaining("lifecycle revision")
    }

    private fun body(caseId: UUID) =
        """{"eventType":"fraud.case_opened","caseId":"$caseId","revision":1,"occurredAt":"2026-09-17T00:00:00Z"}"""
}
