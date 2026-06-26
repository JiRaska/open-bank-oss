// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
                        withholdingTaxRepo.markRemitted(saved.withholdingIds, saved.id).flatMap {
                            eventOutbox.append(remittedEvent(saved)).map { saved }
                        }
                    }
                }
            }
        }

    override fun getRemittance(year: Int, month: Int): Uni<WithholdingRemittance?> =
        remittanceRepo.findByPeriod(year, month)

    override fun listRemittances(): Uni<List<WithholdingRemittance>> = remittanceRepo.findAll()

    /** Builds the versioned `interest.withholding.remitted` outbox event (ADR-0038). */
    private fun remittedEvent(r: WithholdingRemittance): OutboxMessage {
        val payload = "{\"schemaVersion\":1," +
            "\"remittanceId\":\"${r.id}\",\"periodYear\":${r.periodYear},\"periodMonth\":${r.periodMonth}," +
            "\"authority\":\"${r.authority}\",\"currency\":\"${r.currency}\"," +
            "\"totalTaxAmount\":\"${r.totalTaxAmount}\",\"itemCount\":${r.itemCount}," +
            "\"dueDate\":\"${r.dueDate}\",\"status\":\"${r.status}\"}"
        return OutboxMessage(
            eventId = UUID.randomUUID(),
            aggregateId = r.id,
            eventType = "interest.withholding.remitted.v1",
            payload = payload,
        )
    }
}
