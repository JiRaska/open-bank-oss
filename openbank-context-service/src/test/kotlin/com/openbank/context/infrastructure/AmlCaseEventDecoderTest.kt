// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AmlCaseEventDecoderTest {
    private val mapper = ObjectMapper().findAndRegisterModules()
    private val decoder = AmlCaseEventDecoder(mapper)

    @Test
    fun `created event retains only source identities and typed screening observations`() {
        val result = decoder.decode(payload(), EVENT, CREATED)
        assertThat(result).isEqualTo(
            AmlCaseObservation(
                UUID.fromString(EVENT), UUID.fromString(CASE), UUID.fromString(PARTY),
                UUID.fromString(ACCOUNT), UUID.fromString(TRANSACTION), CREATED,
                "OPEN", null, "LOW", "TRANSACTION_MONITORING", Instant.parse(TIME),
            ),
        )
    }

    @Test
    fun `status change uses its source fields and does not fabricate screening or resource context`() {
        val body = mapOf(
            "caseId" to CASE,
            "partyId" to PARTY,
            "previousStatus" to "OPEN",
            "newStatus" to "UNDER_REVIEW",
            "occurredAt" to TIME,
        )
        val result = decoder.decode(mapper.writeValueAsString(body), EVENT, CHANGED)
        assertThat(result.status).isEqualTo("UNDER_REVIEW")
        assertThat(result.previousStatus).isEqualTo("OPEN")
        assertThat(result.accountId).isNull()
        assertThat(result.transactionId).isNull()
        assertThat(result.riskLevel).isNull()
        assertThat(result.screeningType).isNull()
        assertThat(result.occurredAt).isEqualTo(Instant.parse(TIME))
    }

    @Test
    fun `missing headers and unknown types cannot use payload fallback`() {
        assertThatThrownBy { decoder.decode(payload(), null, CREATED) }.hasMessageContaining("eventId")
        assertThatThrownBy { decoder.decode(payload(), EVENT, null) }.hasMessageContaining("ce-type")
        assertThatThrownBy { decoder.decode(payload(), EVENT, "aml.case.unknown.v1") }
            .hasMessageContaining("ce-type")
        assertThatThrownBy { decoder.decode(payload(), "1-1-1-1-1", CREATED) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `missing and malformed source identities are rejected`() {
        listOf("caseId", "partyId").forEach { field ->
            assertThatThrownBy { decoder.decode(payload(omitted = setOf(field)), EVENT, CREATED) }
                .hasMessageContaining(field)
        }
        listOf("caseId", "partyId", "accountId", "transactionId").forEach { field ->
            assertThatThrownBy { decoder.decode(payload(mapOf(field to "invalid")), EVENT, CREATED) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `missing occurrence is distinct from invalid occurrence and neither is replaced by current time`() {
        assertThatThrownBy { decoder.decode(payload(omitted = setOf("occurredAt")), EVENT, CREATED) }
            .hasMessageContaining("missing occurredAt")
        assertThatThrownBy { decoder.decode(payload(mapOf("occurredAt" to "yesterday")), EVENT, CREATED) }
            .isInstanceOf(java.time.format.DateTimeParseException::class.java)
    }

    @Test
    fun `malformed statuses and wrong type specific fields fail`() {
        listOf("status", "riskLevel", "screeningType").forEach { field ->
            assertThatThrownBy { decoder.decode(payload(mapOf(field to "UNKNOWN")), EVENT, CREATED) }
                .hasMessageContaining(field)
        }
        assertThatThrownBy { decoder.decode(payload(), EVENT, CHANGED) }.hasMessageContaining("newStatus")
        assertThatThrownBy {
            decoder.decode(payload(mapOf("newStatus" to "CLEARED", "previousStatus" to "INVALID")), EVENT, CHANGED)
        }.hasMessageContaining("previousStatus")
        assertThatThrownBy { decoder.decode(payload(mapOf("riskLevel" to "HIGH")), EVENT, CREATED) }
            .hasMessageContaining("source risk")
    }

    @Test
    fun `sensitive payload fields cannot enter minimized evidence`() {
        val clean = decoder.decode(payload(), EVENT, CREATED)
        val extra = mapOf(
            "matchedEntity" to "Synthetic Person",
            "customerReference" to "private-reference",
            "decisionReason" to "private reason",
            "assignedAnalyst" to "private-analyst",
            "decidedBy" to "private-decider",
            "alertCode" to "private-alert",
            "idempotencyKey" to "private-key",
        )
        val result = decoder.decode(payload(extra), EVENT, CREATED)
        assertThat(result).isEqualTo(clean)
        assertThat(mapper.writeValueAsString(result))
            .doesNotContain("private-", "Synthetic", "matchedEntity", "alertCode")
    }

    @Test
    fun `payload budget measures UTF8 bytes and rejects non object JSON`() {
        assertThatThrownBy { decoder.decode(payload(mapOf("padding" to "é".repeat(17000))), EVENT, CREATED) }
            .hasMessageContaining("payload budget")
        listOf("null", "[]", "\"text\"").forEach {
            assertThatThrownBy { decoder.decode(it, EVENT, CREATED) }.hasMessageContaining("object")
        }
    }

    private fun payload(extra: Map<String, String> = emptyMap(), omitted: Set<String> = emptySet()): String =
        mapper.writeValueAsString(
            (
                mapOf(
                    "caseId" to CASE,
                    "partyId" to PARTY,
                    "accountId" to ACCOUNT,
                    "transactionId" to TRANSACTION,
                    "status" to "OPEN",
                    "riskLevel" to "LOW",
                    "screeningType" to "TRANSACTION_MONITORING",
                    "occurredAt" to TIME,
                ) + extra
                ).filterKeys { it !in omitted },
        )

    private companion object {
        const val EVENT = "00000000-0000-4000-8000-000000000001"
        const val CASE = "00000000-0000-4000-8000-000000000002"
        const val PARTY = "00000000-0000-4000-8000-000000000003"
        const val ACCOUNT = "00000000-0000-4000-8000-000000000004"
        const val TRANSACTION = "00000000-0000-4000-8000-000000000005"
        const val TIME = "2026-09-01T00:00:00Z"
        const val CREATED = "aml.case.created.v1"
        const val CHANGED = "aml.case.status_changed.v1"
    }
}
