// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.domain.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * IFRS 9 stage, exactly as `openbank-lending-service` last reported it for one loan (ADR-0028 Phase 3
 * / ADR-0037 event ingestion follow-up, issue #638).
 *
 * This is a **read-only projection**, not the AnaCredit credit-dataset model itself
 * ([CreditExposure]): AnaCredit's own domain (scope test, €25k threshold, credit/financial dataset
 * mapping) is unchanged by this addition. The projection exists so the feed can eventually enrich a
 * `LOAN`-instrument-type [CreditExposure] with the debtor's real overdue/stage status instead of being
 * blind to lending's book — wiring that enrichment into [com.openbank.anacredit.domain.report.AnaCreditReturnBuilder]
 * is a separate, later increment; this lands the durable, correctly-ordered projection first.
 *
 * [stage] mirrors `com.openbank.libs.lending.Ifrs9Stage` by name (`STAGE_1`/`STAGE_2`/`STAGE_3`) but is
 * declared as a plain `String` here rather than a shared dependency on `openbank-libs-lending` types —
 * anacredit-service consumes lending's event *payload*, a stable wire contract, not its Kotlin types
 * (services stay independently deployable, ADR-0002).
 *
 * [eventTimestamp] is the ordering key for idempotent consumption: a consumer only applies an incoming
 * event if its `eventTimestamp` is strictly newer than the projection's current value, so a redelivered
 * or out-of-order event can never regress the stage (see `LoanStageEventConsumer`).
 */
data class LoanStageProjection(
    val loanId: UUID,
    val stage: String,
    val daysPastDue: Int,
    val eventTimestamp: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
