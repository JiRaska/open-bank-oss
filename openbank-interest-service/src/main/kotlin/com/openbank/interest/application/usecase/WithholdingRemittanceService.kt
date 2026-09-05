// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.`in`.RemitWithholdingUseCase
import com.openbank.interest.application.port.out.InterestEventOutbox
import com.openbank.interest.application.port.out.WithholdingRemittanceRepository
import com.openbank.interest.application.port.out.WithholdingTaxRepository
import com.openbank.interest.domain.tax.WithholdingRemittance
import com.openbank.interest.domain.tax.WithholdingRemittancePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.UUID

/**
 * Assembles the monthly withholding-tax remittance (ADR-0038) and advances the paired records
 * `RECORDED → REMITTED`. Actual payment to the finanční úřad is delegated via the emitted
 * `interest.withholding.remitted.v1` event — interest-service moves no cash (ADR-0030 off-gate).
 */
@ApplicationScoped
class WithholdingRemittanceService(
    private val withholdingTaxRepo: WithholdingTaxRepository,
    private val remittanceRepo: WithholdingRemittanceRepository,
    private val eventOutbox: InterestEventOutbox,
    private val clock: Clock,
) : RemitWithholdingUseCase {

    @Inject
    constructor(
        withholdingTaxRepo: WithholdingTaxRepository,
        remittanceRepo: WithholdingRemittanceRepository,
        eventOutbox: InterestEventOutbox,
    ) : this(
        withholdingTaxRepo,
        remittanceRepo,
        eventOutbox,
        Clock.systemUTC(),
    )

    override fun assembleRemittance(year: Int, month: Int): Uni<WithholdingRemittance> =
        remittanceRepo.findByPeriod(year, month).flatMap { existing ->
            // Idempotent: one batch per tax period — return the assembled one, re-mark nothing.
            if (existing != null) {
                Uni.createFrom().item(existing)
            } else {
                val ym = YearMonth.of(year, month)
                withholdingTaxRepo.findRecordedForPeriod(ym.atDay(1), ym.atEndOfMonth()).flatMap { records ->
                    val remittance = WithholdingRemittancePolicy.assemble(
                        records,
                        year,
                        month,
                        OffsetDateTime.now(clock),
                    )
                    remittanceRepo.save(remittance).flatMap { saved ->
                        withholdingTaxRepo.markRemitted(saved.withholdingIds, saved.id).flatMap { updated ->
                            if (updated != saved.withholdingIds.size) {
                                // markRemitted only advances rows still RECORDED. A short count means
                                // some row was folded into another batch (or reversed) between the
                                // read and the write — the assembled totals no longer describe what
                                // was actually marked, so emitting the remitted event would ask the
                                // rail to pay a figure we cannot substantiate. Fail the assembly.
                                Uni.createFrom().failure(shortMarkFailure(saved, updated))
                            } else {
                                eventOutbox.append(remittedEvent(saved)).map { saved }
                            }
                        }
                    }
                }
            }
        }

    override fun getRemittance(year: Int, month: Int): Uni<WithholdingRemittance?> =
        remittanceRepo.findByPeriod(year, month)

    override fun listRemittances(): Uni<List<WithholdingRemittance>> = remittanceRepo.findAll()

    override fun settle(remittanceId: UUID): Uni<Unit> = remittanceRepo.markSettled(remittanceId).replaceWith(Unit)

    /** The loud failure for a short `RECORDED → REMITTED` mark — see the call site for why. */
    private fun shortMarkFailure(saved: WithholdingRemittance, updated: Int) = IllegalStateException(
        "Remittance ${saved.id} (${saved.periodYear}-${saved.periodMonth}) aborted: expected to mark " +
            "${saved.withholdingIds.size} RECORDED withholdings REMITTED, matched $updated — concurrent " +
            "assembly or reversed rows; the batch was not emitted.",
    )

    /**
     * Builds the versioned `interest.withholding.remitted` outbox event (ADR-0038).
     *
     * `occurredAt` is [WithholdingRemittance.createdAt] — the instant [assembleRemittance] built
     * this batch, which is the event (#8352). `dueDate` cannot serve: it is a `LocalDate`
     * regulatory deadline, a date the obligation must be met BY, not a moment anything happened.
     * With neither on the wire under a name `AuditConsumer.eventTime` accepts, every audit row for
     * a tax remittance recorded the audit consumer's ingest clock as the assembly time.
     *
     * `.toInstant()` normalises the `OffsetDateTime` to the `Z` form the rest of the fleet emits,
     * rather than relying on `Instant.parse`'s tolerance of an offset.
     *
     * Additive: every existing field keeps its name, place and form.
     */
    private fun remittedEvent(r: WithholdingRemittance): OutboxMessage {
        val payload = "{\"schemaVersion\":1," +
            "\"remittanceId\":\"${r.id}\",\"periodYear\":${r.periodYear},\"periodMonth\":${r.periodMonth}," +
            "\"authority\":\"${r.authority}\",\"currency\":\"${r.currency}\"," +
            "\"totalTaxAmount\":\"${r.totalTaxAmount}\",\"itemCount\":${r.itemCount}," +
            "\"dueDate\":\"${r.dueDate}\",\"status\":\"${r.status}\"," +
            "\"occurredAt\":\"${r.createdAt.toInstant()}\"}"
        return OutboxMessage(
            eventId = UUID.randomUUID(),
            aggregateId = r.id,
            eventType = "interest.withholding.remitted.v1",
            payload = payload,
        )
    }
}
