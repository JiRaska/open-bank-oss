// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.port.out

import com.openbank.finrep.domain.model.BalanceVerdict
import java.time.Duration

/**
 * Outbound observability port (ADR-0002 / ADR-0077 Tier C) for the FINREP/COREP rendering service
 * (ADR-0097).
 *
 * finrep-service is a pure derivation over `openbank-ledger-service`'s trial balance: it stores
 * nothing and emits nothing, so **every** way it can be wrong produces a well-formed 200 response.
 * That is what these meters exist for:
 *
 *  - an empty or truncated trial balance renders a template of honest-looking zeros. `trial_balance
 *    .lines` collapsing is the only place that shows;
 *  - a FINREP balance sheet that does not balance ([TemplateRender.balanced] false) is a regulatory
 *    red flag that nothing currently surfaces — `FinrepTemplate.isBalanced` is computed, serialised,
 *    and then nobody looks;
 *  - COREP C 01.00 reports capital-structure rows as flagged data gaps (ADR-0097: never a silent
 *    gap). The count of gap cells is the measure of how much of the return is not yet real.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the application layer stays free
 * of the metrics framework, and so the counters are exercised through the real adapter (over a
 * `SimpleMeterRegistry`) in unit tests.
 *
 * Implemented by [com.openbank.finrep.infrastructure.observability.FinrepMetricsAdapter].
 */
interface FinrepMetricsPort {

    /** Record one successfully rendered template. */
    fun templateRendered(render: TemplateRender)

    /**
     * Record one render that produced no template.
     *
     * Deliberately takes no template id: the id is a caller-supplied path parameter, so tagging the
     * failure with it would let any client mint unbounded metric series (the cardinality contract).
     * The *rendered* counter can safely carry the id because only the closed set the mappers
     * recognise ever reaches it.
     */
    fun templateFailed(framework: RegulatoryFramework, reason: TemplateFailureReason)
}

/**
 * One rendered template's shape. `balanced` is null for frameworks that define no balance identity
 * (COREP), which the adapter renders as a distinct tag value rather than pretending it balanced.
 *
 * [balanceVerdict] carries WHY `balanced` has the value it has (issue #6011): the two sources
 * agreeing, the two sources disagreeing, or the producer publishing no verdict at all. It is a
 * separate field rather than more values on `balanced` because `balanced` is what a submission
 * gate reads and the verdict is what an operator has to act on, and those are different questions.
 * Null for COREP, exactly like `balanced`.
 */
data class TemplateRender(
    val framework: RegulatoryFramework,
    val templateId: String,
    val trialBalanceLines: Int,
    val cells: Int,
    val dataGapCells: Int,
    val balanced: Boolean?,
    val balanceVerdict: BalanceVerdict?,
    val duration: Duration,
)

/** Which supervisory framework a render belongs to. A bounded set — safe as a tag. */
enum class RegulatoryFramework { FINREP, COREP }

/** Why a render produced no template. A bounded set — safe as a tag. */
enum class TemplateFailureReason {
    /** The requested template id is not one this increment implements. A client error. */
    UNKNOWN_TEMPLATE,

    /** The ledger trial-balance hop failed, so no report can be produced at all. An outage. */
    LEDGER_UNAVAILABLE,
}
