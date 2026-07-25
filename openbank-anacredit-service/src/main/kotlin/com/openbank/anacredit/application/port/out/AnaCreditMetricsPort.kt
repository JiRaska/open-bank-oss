// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.application.port.out

import com.openbank.anacredit.domain.model.InstrumentType
import java.time.Duration

/**
 * Outbound observability port (ADR-0002 / ADR-0077 Tier C). AnaCredit is a *derive-only* regulatory
 * feed: it posts no money and emits no events, so nothing downstream complains when it goes wrong.
 * The two ways it fails are both silent by construction, and both are what these meters make visible:
 *
 *  - the return is assembled from whatever exposures happen to be stored, so a stalled intake
 *    produces a **smaller but perfectly well-formed** return that is submitted to the regulator;
 *  - the lending `loan.stage_changed` consumer is poison-pill safe — a malformed or foreign event is
 *    logged and **acked**, so the IFRS-9 stage projection silently stops advancing.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the application layer stays free
 * of the metrics framework, and so the counters are exercised through the real adapter (over a
 * `SimpleMeterRegistry`) in unit tests.
 *
 * Implemented by [com.openbank.anacredit.infrastructure.observability.AnaCreditMetricsAdapter].
 */
interface AnaCreditMetricsPort {

    /** Record one exposure intake (register or replace). */
    fun exposureRegistered(instrumentType: InstrumentType, currency: String, defaulted: Boolean)

    /**
     * Record one rendered AnaCredit credit-dataset return: how long it took, how many rows it
     * carries, and how many instruments were dropped by the eligibility policy. `records` collapsing
     * or `exclusions` spiking is the only signal that the feed is under-reporting.
     */
    fun returnBuilt(recordCount: Int, exclusionCount: Int, duration: Duration)

    /** Record the outcome of one consumed `loan.stage_changed` event. */
    fun loanStageEvent(outcome: LoanStageEventOutcome)
}

/**
 * Terminal outcomes of one `loan.stage_changed` consumption. A bounded set — safe as a metric tag.
 *
 * [STALE] and [IGNORED] are normal; [PARSE_ERROR], [MALFORMED] and [APPLY_ERROR] are the acked-and-
 * dropped population that previously left nothing but a log line behind.
 */
enum class LoanStageEventOutcome {
    /** The projection was advanced. */
    APPLIED,

    /** A valid event whose timestamp is not newer than the stored projection — correctly discarded. */
    STALE,

    /** A lending event of some other type — not ours to handle. */
    IGNORED,

    /** The payload is not JSON. */
    PARSE_ERROR,

    /** JSON, but missing a `loanId` or `newStage`. */
    MALFORMED,

    /** The projection write failed. Acked anyway so the consumer group is not wedged. */
    APPLY_ERROR,
}
