// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What initiated a close run (ADR-0069 D3 / issue #470). */
enum class CloseTrigger { SCHEDULED, MANUAL }

/** Lifecycle of a close run: in flight, clean, or finished with at least one pocket failure. */
enum class CloseRunStatus { RUNNING, COMPLETED, COMPLETED_WITH_FAILURES }

/** Why a single pocket close failed — drives retry expectation and alerting. */
enum class CloseFailureReason {
    /** Fail-closed reconciliation mismatch (ADR-0035 §E) — needs investigation, retry won't help alone. */
    RECONCILIATION,

    /** A dependent read (account/transaction/balance service) failed — usually transient, retryable. */
    UPSTREAM,

    /**
     * Account is not viable for statement production: empty IBAN or no balance record.
     * Debris from broken early-onboarding attempts. SKIPPED in the run counts (not FAILED),
     * so the StatementCloseFailures alert does not fire for data noise (#862).
     */
    NOT_VIABLE,

    /** Any other unexpected error. */
    UNKNOWN,
}

/**
 * The durable outcome of one scheduled/manual close pass. Operational telemetry, not statement
 * content: it records how many pockets were enumerated/closed/failed/skipped so an operator (and the
 * monthly-cron go/no-go decision) can see that the cadence ran and converged. Aggregated counts are
 * accumulated as the run proceeds; [finishedAt]/[status] are stamped at the end.
 */
data class CloseRun(
    val id: UUID,
    val trigger: CloseTrigger,
    val status: CloseRunStatus,
    val periodFrom: LocalDate?,
    val periodTo: LocalDate?,
    val accountsEnumerated: Int,
    val pocketsClosed: Int,
    val pocketsFailed: Int,
    val pocketsSkipped: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

/** A single per-pocket failure captured within a [CloseRun]. */
data class CloseFailure(
    val id: UUID,
    val runId: UUID,
    val accountId: UUID,
    val pocketCurrency: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val reason: CloseFailureReason,
    val detail: String?,
    val failedAt: Instant,
)
