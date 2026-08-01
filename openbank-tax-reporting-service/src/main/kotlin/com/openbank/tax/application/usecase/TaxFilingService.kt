// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.tax.application.port.out.ObservedRemittanceRepository
import com.openbank.tax.application.port.out.TaxFilingRepository
import com.openbank.tax.domain.model.FilingPeriod
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxFilingRecord
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant

/** No filing record for the requested period. Mapped to 404. */
class TaxFilingNotFoundException(message: String) : RuntimeException(message)

/**
 * The §38d filing (ADR-0180): consume, aggregate, assemble, record what was filed.
 *
 * Owner question this settles: ADR-0038 assembled and remitted the withholding **cash leg** and
 * delegated the statutory **filing** to "the downstream payment/reporting consumer" without naming
 * one, so nobody consumed `interest.withholding.remitted.v1` for filing purposes and the return was
 * never produced. This service is that consumer.
 */
@ApplicationScoped
class TaxFilingService(
    private val remittanceRepository: ObservedRemittanceRepository,
    private val filingRepository: TaxFilingRepository,
    private val accountingClock: AccountingClock,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(TaxFilingService::class.java)

    /**
     * Record a remittance batch observed on the topic, opening its filing period if this is the
     * first one seen for that month.
     *
     * Returns false when the batch was already recorded. Idempotency is keyed on the remittance id
     * rather than on offsets: Kafka is at-least-once and this service is a second consumer group,
     * so a redelivery after a rebalance is normal, not exceptional.
     */
    suspend fun observe(remittance: ObservedRemittance): Boolean {
        val filing = filingRepository.openIfAbsent(
            TaxFilingRecord.open(remittance.period, remittance.currency),
        )

        val recorded = remittanceRepository.record(remittance)
        if (!recorded) return false

        // A batch arriving for a period that is no longer OPEN is a real discrepancy, not something
        // to swallow: the return for that month may already have been filed with a smaller total.
        // It is stored (so the evidence exists) and surfaced loudly rather than silently re-totalled.
        if (!filing.status.acceptsRemittances) {
            log.errorf(
                "Late remittance %s landed in %s filing for period %s — the assembled return does " +
                    "not include it; a correcting return (dodatečné vyúčtování) is needed",
                remittance.remittanceId,
                filing.status,
                remittance.period.label,
            )
        }
        return true
    }

    /** Assemble the period from what has been observed, freezing its totals. */
    suspend fun assemble(period: FilingPeriod, by: String): TaxFilingRecord {
        val filing = filingRepository.findByPeriod(period)
            ?: throw TaxFilingNotFoundException("No filing for period ${period.label}")

        requirePeriodHasEnded(period)

        val totals = remittanceRepository.totalsFor(period)
        if (totals.currencies.size > 1) {
            // §38d withholding is CZK-only (ADR-0033 §E). More than one currency means either the
            // withholding rules changed or the wrong events were consumed — both need a human.
            throw TaxConflictException(
                "Period ${period.label} has remittances in ${totals.currencies.sorted()} — a §38d " +
                    "return covers one currency; refusing to assemble a mixed-currency filing",
            )
        }

        return filingRepository.save(
            filing.assemble(
                totalTaxAmount = totals.totalTaxAmount,
                remittanceCount = totals.remittanceCount,
                itemCount = totals.itemCount,
                by = by,
                at = Instant.now(clock),
            ),
            expectedVersion = filing.version,
        )
    }

    /** Record that the assembled return was submitted, with its FÚ/EPO reference. */
    suspend fun markFiled(period: FilingPeriod, reference: String, by: String): TaxFilingRecord {
        val filing = filingRepository.findByPeriod(period)
            ?: throw TaxFilingNotFoundException("No filing for period ${period.label}")
        return filingRepository.save(
            filing.markFiled(reference, by, Instant.now(clock)),
            expectedVersion = filing.version,
        )
    }

    suspend fun get(period: FilingPeriod): TaxFilingRecord = filingRepository.findByPeriod(period)
        ?: throw TaxFilingNotFoundException("No filing for period ${period.label}")

    suspend fun remittancesFor(period: FilingPeriod): List<ObservedRemittance> =
        remittanceRepository.findByPeriod(period)

    suspend fun list(): List<TaxFilingRecord> = filingRepository.findAll()

    /** Filings past their statutory deadline and still not FILED — the thing worth alerting on. */
    suspend fun overdue(): List<TaxFilingRecord> {
        val today = accountingClock.today()
        return filingRepository.findAll().filter { it.isOverdueAt(today) }
    }

    /**
     * A month that has not ended cannot be assembled: more withholding can still be remitted into
     * it, so the totals would be partial while wearing the label of a return. Uses the ADR-0207
     * accounting-day authority, not a wall clock — a filing deadline is an accounting date.
     */
    private fun requirePeriodHasEnded(period: FilingPeriod) {
        val today = accountingClock.today()
        if (!period.lastDay.isBefore(today)) {
            throw TaxConflictException(
                "Period ${period.label} has not ended (accounting day is $today) — cannot assemble a partial month",
            )
        }
    }
}
