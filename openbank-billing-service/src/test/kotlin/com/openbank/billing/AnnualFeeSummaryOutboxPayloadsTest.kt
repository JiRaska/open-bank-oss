// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.billing.domain.AnnualFeeSummary
import com.openbank.billing.domain.AnnualFeeSummaryLine
import com.openbank.billing.infrastructure.outbox.AnnualFeeSummaryOutboxPayloads
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Locks the `billing.annual-fee-summary.ready` outbox/Kafka payload shape (ADR-0248) — the exact
 * contract a parallel PR builds document-service's consumer against. Asserts field names, the
 * literal `eventType` value, that every monetary field is a JSON STRING (never a number, the
 * fleet-wide "never a float" convention), and that a `null` interestRate is omitted rather than
 * serialized as `"interestRate":null`.
 */
class AnnualFeeSummaryOutboxPayloadsTest {

    private val mapper = ObjectMapper()

    @Test
    fun `serializes every contract field with the exact names and the AnnualFeeSummaryReady eventType`() {
        val summary = AnnualFeeSummary(
            accountId = "11111111-1111-1111-1111-111111111111",
            partyRef = "22222222-2222-2222-2222-222222222222",
            year = 2026,
            currency = "CZK",
            fees = listOf(
                AnnualFeeSummaryLine("maintenance", "Monthly account maintenance", "Account fee", BigDecimal("123.45")),
            ),
            totalFees = BigDecimal("123.45"),
            interestRate = BigDecimal("0.50"),
        )
        val occurredAt = Instant.parse("2026-01-15T05:00:00Z")

        val json = AnnualFeeSummaryOutboxPayloads.toJson(summary, occurredAt)
        val node = mapper.readTree(json) as ObjectNode

        assertThat(node.get("eventType").asText()).isEqualTo("AnnualFeeSummaryReady")
        assertThat(node.get("accountId").asText()).isEqualTo(summary.accountId)
        assertThat(node.get("partyRef").asText()).isEqualTo(summary.partyRef)
        assertThat(node.get("year").asInt()).isEqualTo(2026)
        assertThat(node.get("currency").asText()).isEqualTo("CZK")
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-01-15T05:00:00Z")

        // Monetary fields are JSON strings, never numbers.
        assertThat(node.get("totalFees").isTextual).isTrue()
        assertThat(node.get("totalFees").asText()).isEqualTo("123.45")
        assertThat(node.get("interestRate").isTextual).isTrue()
        assertThat(node.get("interestRate").asText()).isEqualTo("0.50")

        val fees = node.get("fees")
        assertThat(fees.isArray).isTrue()
        assertThat(fees).hasSize(1)
        val line = fees[0]
        assertThat(line.get("code").asText()).isEqualTo("maintenance")
        assertThat(line.get("name").asText()).isEqualTo("Monthly account maintenance")
        assertThat(line.get("category").asText()).isEqualTo("Account fee")
        assertThat(line.get("amount").isTextual).isTrue()
        assertThat(line.get("amount").asText()).isEqualTo("123.45")
    }

    @Test
    fun `omits interestRate entirely (not a null field) when billing has no source for it`() {
        val summary = AnnualFeeSummary(
            accountId = "acc-1",
            partyRef = "party-1",
            year = 2026,
            currency = "CZK",
            fees = emptyList(),
            totalFees = BigDecimal.ZERO,
            interestRate = null,
        )

        val json = AnnualFeeSummaryOutboxPayloads.toJson(summary, Instant.parse("2026-01-15T05:00:00Z"))
        val node = mapper.readTree(json) as ObjectNode

        assertThat(node.has("interestRate")).isFalse()
    }

    @Test
    fun `an empty fees list serializes as an empty array, not null or absent`() {
        val summary = AnnualFeeSummary(
            accountId = "acc-1",
            partyRef = "party-1",
            year = 2026,
            currency = "CZK",
            fees = emptyList(),
            totalFees = BigDecimal.ZERO,
            interestRate = null,
        )

        val json = AnnualFeeSummaryOutboxPayloads.toJson(summary, Instant.parse("2026-01-15T05:00:00Z"))
        val node = mapper.readTree(json) as ObjectNode

        assertThat(node.get("fees").isArray).isTrue()
        assertThat(node.get("fees")).isEmpty()
        assertThat(node.get("totalFees").asText()).isEqualTo("0")
    }
}
