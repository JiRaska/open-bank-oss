// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.outbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.billing.infrastructure.persistence.entity.AssessedFeeEntity

/**
 * Serializes an [AssessedFeeEntity] into the `billing.fee.post-intent.v1` /
 * `billing.fee.reversal-intent.v1` `billing_outbox` payload shapes (ADR-0143 steps 2/2e).
 *
 * **Uses Jackson, not hand-built string concatenation (#4701).** `fee_name` (`feeName`, wired
 * into `description` below) is free text sourced from the product catalog — no length or
 * character constraint on the column (`BillingEntities.kt`'s `@Column(name = "fee_name",
 * nullable = false)`) — and can contain a double quote or backslash. The previous version of
 * this object hand-built the JSON string, embedding `feeName` with no escaping at all; a fee
 * name containing either character produced malformed JSON that
 * [LedgerOutboxEventPublisher.publish] could never parse. That failure is deterministic, not
 * transient — `OutboxDispatch.dispatchOnce` re-claims and re-attempts the same row every tick,
 * `mapper.readValue` throws the same `JsonParseException` every time, and after
 * `OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS` (10) the row is parked `DEAD` with no alert (#4701:
 * two `billing.fee.post-intent.v1` rows found dead at `attempt_count = 10`). This is the exact
 * trap [AnnualFeeSummaryOutboxPayloads]'s own KDoc already named for the same reason ("fee
 * names/categories carry free text ... possibly quotes, so hand-rolled string escaping is the
 * wrong tool here") — that object was migrated to Jackson under ADR-0248, this one was not.
 *
 * `internal`, own file (mirrors [AnnualFeeSummaryOutboxPayloads]): this is the exact payload
 * shape [LedgerOutboxEventPublisher] deserializes against, so it needs a payload-shape unit test
 * with no database in the loop — impossible while nested `private` inside
 * `BillingAssessmentRepositoryImpl`.
 *
 * Monetary fields stay JSON STRINGS (`amount.toPlainString()`), matching the previous wire
 * format and every other money-carrying event in this fleet ("never a float").
 */
internal object AssessedFeeOutboxPayloads {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    fun postIntent(fee: AssessedFeeEntity): String = mapper.writeValueAsString(
        FeePostIntentWirePayload(
            idempotencyKey = fee.idempotencyKey,
            cycleId = fee.cycleId,
            accountId = fee.accountId,
            feeId = fee.feeId,
            amount = fee.chargedAmount.toPlainString(),
            currency = fee.currency,
            description = "Fee charge: ${fee.feeName}",
        ),
    )

    fun reversalIntent(fee: AssessedFeeEntity, reason: String): String = mapper.writeValueAsString(
        FeeReversalIntentWirePayload(
            idempotencyKey = fee.reversalIdempotencyKey(),
            originalIdempotencyKey = fee.idempotencyKey,
            cycleId = fee.cycleId,
            accountId = fee.accountId,
            feeId = fee.feeId,
            amount = fee.chargedAmount.toPlainString(),
            currency = fee.currency,
            reason = reason,
        ),
    )

    private fun AssessedFeeEntity.reversalIdempotencyKey(): String = "fee-reversal-$cycleId-$accountId-$feeId-$currency"
}

/**
 * The `billing.fee.post-intent.v1` outbox payload shape — mirrors
 * `LedgerOutboxEventPublisher`'s private `FeePostIntentWirePayload` deserialization target field
 * for field, so a rename on either side must be made on both (no shared module boundary here;
 * this repo's per-service outbox payloads intentionally don't share a wire-contract type).
 */
private data class FeePostIntentWirePayload(
    val schemaVersion: Int = 1,
    val idempotencyKey: String,
    val cycleId: String,
    val accountId: String,
    val feeId: String,
    val amount: String,
    val currency: String,
    val description: String,
)

/** The `billing.fee.reversal-intent.v1` outbox payload shape — see [FeePostIntentWirePayload]. */
private data class FeeReversalIntentWirePayload(
    val schemaVersion: Int = 1,
    val idempotencyKey: String,
    val originalIdempotencyKey: String,
    val cycleId: String,
    val accountId: String,
    val feeId: String,
    val amount: String,
    val currency: String,
    val reason: String,
)
