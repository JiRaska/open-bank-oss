// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.outbox

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.billing.domain.AnnualFeeSummary
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Serializes an [AnnualFeeSummary] into the `billing.annual-fee-summary.ready` outbox payload
 * (ADR-0248) — the exact contract document-service's Kafka consumer is built against. Uses
 * Jackson (unlike `BillingAssessmentRepositoryImpl`'s `AssessedFeeOutboxPayloads` hand-built
 * string concatenation for the charge/reversal payloads): the fee list has an unbounded number of
 * entries and fee names/categories carry free text (Czech diacritics, possibly quotes), so
 * hand-rolled string escaping is the wrong tool here — this mirrors `LedgerOutboxEventPublisher`'s
 * own `jacksonObjectMapper()` use elsewhere in this service. `internal` (not `private`), split out
 * of `BillingAssessmentRepositoryImpl` — its own file, on purpose: this is the exact payload shape
 * a parallel consumer is built against, so it needs a payload-shape unit test with no database in
 * the loop, which a `private` declaration nested in the repository impl file could not have.
 *
 * All monetary fields are serialized as JSON STRINGS (`"123.45"`, [java.math.BigDecimal]'s own
 * `toPlainString()`), never JSON numbers — the same "never a float" contract every other
 * money-carrying event in this fleet follows.
 */
internal object AnnualFeeSummaryOutboxPayloads {

    /** `"AnnualFeeSummaryReady"` — the contract's `eventType` literal (ADR-0248). */
    const val EVENT_TYPE_LITERAL: String = "AnnualFeeSummaryReady"

    private val mapper = jacksonObjectMapper().findAndRegisterModules().apply {
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    fun toJson(summary: AnnualFeeSummary, occurredAt: Instant): String = mapper.writeValueAsString(
        AnnualFeeSummaryReadyPayload(
            accountId = summary.accountId,
            partyRef = summary.partyRef,
            year = summary.year,
            currency = summary.currency,
            fees = summary.fees.map {
                AnnualFeeSummaryReadyPayload.FeeLine(
                    code = it.code,
                    name = it.name,
                    category = it.category,
                    amount = it.amount.toPlainString(),
                )
            },
            totalFees = summary.totalFees.toPlainString(),
            interestRate = summary.interestRate?.toPlainString(),
            occurredAt = DateTimeFormatter.ISO_INSTANT.format(occurredAt),
        ),
    )
}

/**
 * The `billing.annual-fee-summary.ready` outbox/Kafka payload shape (ADR-0248) — field names,
 * types and the [AnnualFeeSummaryOutboxPayloads.EVENT_TYPE_LITERAL] value are the exact contract
 * document-service's consumer is built against; do not rename without coordinating that change.
 */
internal data class AnnualFeeSummaryReadyPayload(
    val eventType: String = AnnualFeeSummaryOutboxPayloads.EVENT_TYPE_LITERAL,
    val accountId: String,
    val partyRef: String,
    val year: Int,
    val currency: String,
    val fees: List<FeeLine>,
    val totalFees: String,
    val interestRate: String?,
    val occurredAt: String,
) {
    data class FeeLine(val code: String, val name: String, val category: String, val amount: String)
}
