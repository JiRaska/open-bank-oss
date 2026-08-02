// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.port.out

import com.openbank.tax.domain.model.FilingPeriod
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.model.TaxFilingRecord

/** Aggregate of the remittances observed for one period. */
data class RemittanceTotals(
    val remittanceCount: Int,
    val itemCount: Int,
    val totalTaxAmount: java.math.BigDecimal,
    val currencies: Set<String>,
)

interface ObservedRemittanceRepository {
    /**
     * Record an observed remittance. Returns false if [remittance] was already recorded — Kafka is
     * at-least-once, so re-delivery MUST be a no-op or the return overstates the tax withheld.
     */
    suspend fun record(remittance: ObservedRemittance): Boolean

    suspend fun findByPeriod(period: FilingPeriod): List<ObservedRemittance>

    suspend fun totalsFor(period: FilingPeriod): RemittanceTotals
}

interface TaxFilingRepository {
    suspend fun findByPeriod(period: FilingPeriod): TaxFilingRecord?

    /**
     * Named `listFilings`, not `findAll`: the Panache adapter also implements
     * `PanacheRepositoryBase`, whose own `findAll(): PanacheQuery<Entity>` collides with it —
     * same name, unrelated return type, so the class does not compile.
     */
    suspend fun listFilings(): List<TaxFilingRecord>

    /** Insert if absent, else return the existing row — the consumer races itself across partitions. */
    suspend fun openIfAbsent(record: TaxFilingRecord): TaxFilingRecord

    /** Conditional update guarded on the expected version, so two operators cannot both win. */
    suspend fun save(record: TaxFilingRecord, expectedVersion: Long): TaxFilingRecord
}

/**
 * Renders the GFŘ **EPO** XML for *Vyúčtování daně vybírané srážkou podle zvláštní sazby daně*.
 *
 * **Deliberately unimplemented in this increment.** The EPO XSD is a specific published schema, and
 * a plausible-looking guess at it would produce a file that passes every gate in this repo and is
 * rejected — or worse, accepted — by the finanční úřad. A wrong tax return is worse than no tax
 * return, so the port exists to fix the seam while the rendering waits for the real schema.
 *
 * The rest of the service is useful without it: the aggregation is the part that had no owner
 * (ADR-0038 self-flagged this gap), and an operator can read the assembled totals off the API and
 * key them into the EPO portal today.
 */
interface EpoRendererPort {
    /** True when a real renderer is bound; the API reports this rather than implying one exists. */
    val available: Boolean

    /** @throws UnsupportedOperationException while [available] is false. */
    suspend fun renderVyuctovani(filing: TaxFilingRecord, remittances: List<ObservedRemittance>): ByteArray
}
