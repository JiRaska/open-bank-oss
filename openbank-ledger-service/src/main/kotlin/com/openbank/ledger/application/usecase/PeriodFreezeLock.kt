// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.out.ClosedPeriodRepository
import com.openbank.ledger.domain.model.ClosedPeriodRecord
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.LocalDate

/**
 * The period-granular lock (ADR-0096 D1): no journal may be dated into a FROZEN period.
 *
 * ## Where this sits among the three locks
 *
 * The posting path now consults, in order of tightening constraint:
 *  1. the **day** lock (ADR-0207 D3) — is this accounting day still OPEN;
 *  2. this **period** lock — is the month/quarter/year containing it FROZEN;
 *  3. the **fiscal-year** lock (#869) — is the year ATTESTED.
 *
 * They are not redundant. A day can be LOCKED inside an unfrozen month (end-of-day tie-out ran, the
 * statutory close has not). A month can be FROZEN inside an unattested year (monthly close, annual
 * attestation pending). Collapsing them would mean one of those states could not be expressed.
 *
 * ## Shadow first, for the same reason as the day lock
 *
 * Ships in `shadow`: the check runs and records what it *would* have refused without refusing, so
 * the volume of postings landing in already-frozen periods is measured before any start failing.
 * Turning on a new money-path refusal blind is how #1197 killed five workloads for four days. The
 * evidence to read before flipping is
 * `openbank_ledger_period_lock_decisions_total{outcome="would_refuse"}`.
 *
 * A date with no frozen period covering it is **not** refused in any mode — absence of a close is
 * not evidence of one, and failing closed on absence would brick every posting the moment this
 * ships, before a single period has been frozen.
 */
@ApplicationScoped
class PeriodFreezeLock(
    private val closedPeriodRepository: ClosedPeriodRepository,
    private val meterRegistry: MeterRegistry,
    @ConfigProperty(name = "openbank.ledger.period-lock.mode", defaultValue = MODE_SHADOW) private val mode: String,
) {
    private val log = Logger.getLogger(PeriodFreezeLock::class.java)

    /** True when a refusal is actually raised rather than only recorded. */
    val enforcing: Boolean get() = mode == MODE_ENFORCE

    /**
     * The frozen period sealing [entryDate], or null. **Never throws** — the caller decides, exactly
     * as with the day lock: a posting into a sealed period is refused, a reversal out of one is
     * routed forward instead, and both need the same measurement.
     */
    suspend fun evaluate(entryDate: LocalDate, operation: String): ClosedPeriodRecord? {
        if (mode == MODE_OFF) return null

        val frozen = closedPeriodRepository.findFrozenContaining(entryDate)
        record(frozen, operation)
        return frozen
    }

    /** Evaluate and, in [MODE_ENFORCE] only, refuse. The posting path's guard. */
    suspend fun requireOpen(entryDate: LocalDate, operation: String) {
        val frozen = evaluate(entryDate, operation)
        if (frozen != null && enforcing) {
            throw FrozenPeriodException(
                "Accounting period ${frozen.period.label} is FROZEN — no journal may be dated into it; " +
                    "book the correction into the current open period instead",
            )
        }
    }

    private fun record(frozen: ClosedPeriodRecord?, operation: String) {
        val outcome = when {
            frozen == null -> OUTCOME_ALLOWED
            enforcing -> OUTCOME_REFUSED
            else -> OUTCOME_WOULD_REFUSE
        }
        meterRegistry.counter(
            "openbank.ledger.period_lock.decisions",
            "mode",
            mode,
            "outcome",
            outcome,
            "operation",
            operation,
            "period_type",
            frozen?.period?.type?.name ?: "NONE",
        ).increment()

        if (outcome == OUTCOME_WOULD_REFUSE && frozen != null) {
            log.warnf(
                "Period lock SHADOW: %s dated into FROZEN %s would be refused under enforce mode",
                operation,
                frozen.period.label,
            )
        }
    }

    companion object {
        const val MODE_OFF = "off"
        const val MODE_SHADOW = "shadow"
        const val MODE_ENFORCE = "enforce"

        private const val OUTCOME_ALLOWED = "allowed"
        private const val OUTCOME_WOULD_REFUSE = "would_refuse"
        private const val OUTCOME_REFUSED = "refused"
    }
}

/**
 * A posting targeted a FROZEN statutory period (ADR-0096 D1). Mapped to 409.
 *
 * A distinct type from [ClosedAccountingDayException] and [ClosedFiscalPeriodException] even though
 * all three are 409: the three locks have different granularity and different remedies, and the
 * caller can only tell them apart if the exception does.
 */
class FrozenPeriodException(message: String) : RuntimeException(message)
