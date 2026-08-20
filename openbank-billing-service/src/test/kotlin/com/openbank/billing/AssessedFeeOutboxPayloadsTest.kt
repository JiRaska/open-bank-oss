// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.billing.domain.PostingStatus
import com.openbank.billing.infrastructure.outbox.AssessedFeeOutboxPayloads
import com.openbank.billing.infrastructure.persistence.entity.AssessedFeeEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Locks the `billing.fee.post-intent.v1` / `billing.fee.reversal-intent.v1` outbox payload shape
 * (ADR-0143) — the exact contract [com.openbank.billing.infrastructure.outbox.LedgerOutboxEventPublisher]
 * deserializes against.
 *
 * The whole point of this suite (#4701): a `fee_name` containing a double quote or a backslash
 * previously produced MALFORMED JSON — `postIntent`/`reversalIntent` hand-built the string and
 * embedded `feeName` with no escaping. That JSON never parsed, `LedgerOutboxEventPublisher.publish`
 * threw the same `JsonParseException` on every dispatch attempt (a permanent failure, not a
 * transient one — `OutboxDispatch.isTransportUnavailable` does not cover it, so every attempt
 * counted), and after 10 attempts the row was parked `DEAD` with no alert. Two
 * `billing.fee.post-intent.v1` rows were found in exactly that state. These tests prove the
 * Jackson-based replacement produces valid, round-trippable JSON for fee names carrying the
 * characters that broke it.
 */
class AssessedFeeOutboxPayloadsTest {

    private val mapper = ObjectMapper()

    private fun fee(feeName: String = "Maintenance", idempotencyKey: String = "fee-2026-07-acc-1-f1-CZK") =
        AssessedFeeEntity().apply {
            assessmentId = java.util.UUID.randomUUID()
            cycleId = "2026-07"
            accountId = "acc-1"
            feeId = "f1"
            this.feeName = feeName
            currency = "CZK"
            chargedAmount = BigDecimal("150.00")
            waived = false
            waiveReason = "NOT_WAIVABLE"
            this.idempotencyKey = idempotencyKey
            postingStatus = PostingStatus.PENDING
        }

    @Test
    fun `postIntent serializes the exact contract fields as valid JSON`() {
        val json = AssessedFeeOutboxPayloads.postIntent(fee())
        val node = mapper.readTree(json) as ObjectNode

        assertThat(node.get("schemaVersion").asInt()).isEqualTo(1)
        assertThat(node.get("idempotencyKey").asText()).isEqualTo("fee-2026-07-acc-1-f1-CZK")
        assertThat(node.get("cycleId").asText()).isEqualTo("2026-07")
        assertThat(node.get("accountId").asText()).isEqualTo("acc-1")
        assertThat(node.get("feeId").asText()).isEqualTo("f1")
        assertThat(node.get("amount").isTextual).isTrue()
        assertThat(node.get("amount").asText()).isEqualTo("150.00")
        assertThat(node.get("currency").asText()).isEqualTo("CZK")
        assertThat(node.get("description").asText()).isEqualTo("Fee charge: Maintenance")
    }

    @Test
    fun `a fee name containing a double quote produces valid JSON, not the old malformed payload`() {
        val json = AssessedFeeOutboxPayloads.postIntent(fee(feeName = """Fee for "Premium" tier"""))

        val node = mapper.readTree(json) as ObjectNode // throws on malformed JSON — the #4701 failure mode

        assertThat(node.get("description").asText()).isEqualTo("""Fee charge: Fee for "Premium" tier""")
    }

    @Test
    fun `a fee name containing a backslash produces valid JSON`() {
        val json = AssessedFeeOutboxPayloads.postIntent(fee(feeName = """Fee \ surcharge"""))

        val node = mapper.readTree(json) as ObjectNode

        assertThat(node.get("description").asText()).isEqualTo("""Fee charge: Fee \ surcharge""")
    }

    @Test
    fun `a fee name with Czech diacritics round-trips unchanged`() {
        val json = AssessedFeeOutboxPayloads.postIntent(fee(feeName = "Vedení účtu"))

        val node = mapper.readTree(json) as ObjectNode

        assertThat(node.get("description").asText()).isEqualTo("Fee charge: Vedení účtu")
    }

    @Test
    fun `reversalIntent serializes the exact contract fields, including a reason with quotes and newlines`() {
        val reason = "waiver \"bug\"\nreverting"
        val json = AssessedFeeOutboxPayloads.reversalIntent(fee(), reason)
        val node = mapper.readTree(json) as ObjectNode

        assertThat(node.get("schemaVersion").asInt()).isEqualTo(1)
        assertThat(node.get("idempotencyKey").asText()).isEqualTo("fee-reversal-2026-07-acc-1-f1-CZK")
        assertThat(node.get("originalIdempotencyKey").asText()).isEqualTo("fee-2026-07-acc-1-f1-CZK")
        assertThat(node.get("reason").asText()).isEqualTo(reason)
    }
}
