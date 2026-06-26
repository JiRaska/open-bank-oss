// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.interest.application.port.out

import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingRemittance
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

/**
 * Outbound port resolving the tax profile of an account's interest beneficiary (ADR-0033 §C).
 *
 * v1 ships a CZ-resident-individual default provider; account→party tax-attribute resolution is a
 * documented fast-follow. Implementations MUST fail safe — never propagate a failure that would skip
 * withholding; resolve to [TaxProfile.FAIL_SAFE_DEFAULT] instead.
 */
interface TaxProfilePort {
    fun resolve(accountId: UUID): Uni<TaxProfile>
}

/** Outbound persistence port for the withholding-tax liability ledger (reactive, Mutiny). */
interface WithholdingTaxRepository {
    fun save(withholding: WithholdingTax): Uni<WithholdingTax>
    fun findByAccountId(accountId: UUID): Uni<List<WithholdingTax>>
    fun findByCapitalizationId(capitalizationId: UUID): Uni<WithholdingTax?>

    /** All `RECORDED` withholding rows whose §38d credit date (`periodTo`) falls in `[from, to]` (ADR-0038). */
    fun findRecordedForPeriod(from: LocalDate, to: LocalDate): Uni<List<WithholdingTax>>

    /** Advance the given rows `RECORDED → REMITTED`, stamping [remittanceId]. Returns the rows updated. */
    fun markRemitted(ids: List<UUID>, remittanceId: UUID): Uni<Int>
}

/** Outbound persistence port for the monthly withholding-remittance batch ledger (ADR-0038). */
interface WithholdingRemittanceRepository {
    fun save(remittance: WithholdingRemittance): Uni<WithholdingRemittance>
    fun findByPeriod(year: Int, month: Int): Uni<WithholdingRemittance?>
    fun findAll(): Uni<List<WithholdingRemittance>>
}

/**
 * Outbound port appending a domain event into the transactional outbox (reactive).
 *
 * Distinct from [InterestOutboxRepository] (the suspend-based dispatcher side); this is the write
 * side used inside the reactive capitalization chain so the event lands with the aggregate change.
 */
interface InterestEventOutbox {
    fun append(message: OutboxMessage): Uni<Void>
}
