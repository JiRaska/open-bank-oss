// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.port.out

import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import com.openbank.tax.domain.returns.StatutoryReturn
import java.math.BigDecimal
import java.util.UUID

/** The versioned jurisdiction catalogues this deployment reports under (ADR-0336). */
interface ReturnCatalogueSource {
    fun catalogues(): List<ReturnCatalogue>
}

/** The source system cannot (yet) supply a return's figures. Mapped to 503 — never to zeroes. */
class ReturnDataUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Extraction seam for a return's datapoints (ADR-0336 D4).
 *
 * For the pension catalogue the owners are pension-service (contributions, payouts, participants)
 * and pension-fund-service (fund balance sheet, units, holdings); `PensionReturnDataAdapter` reads
 * their reporting read models (#12425). A return whose figures no service owns, or whose source
 * cannot answer, throws [ReturnDataUnavailableException]: a return is either assembled from real
 * figures or not assembled at all.
 */
interface ReturnDataPort {
    /** True when a real source is bound; the API reports this rather than implying one exists. */
    val available: Boolean

    /** @throws ReturnDataUnavailableException when the figures cannot be sourced. */
    suspend fun fetch(
        catalogue: ReturnCatalogue,
        definition: ReturnDefinition,
        entityId: String,
        period: ReportingPeriod,
    ): Map<String, BigDecimal>
}

/**
 * Renders a return in the regulator's wire format. Unbound in this increment: the cell-level data
 * dictionary was not verified (catalogue `wireFormatVerified=false`), and a plausible guess at a
 * supervisory file format is worse than none — same stance as [EpoRendererPort].
 */
interface ReturnWireRendererPort {
    val available: Boolean

    /** @throws UnsupportedOperationException while [available] is false. */
    suspend fun render(statutoryReturn: StatutoryReturn): ByteArray
}

interface StatutoryReturnRepository {
    suspend fun insert(statutoryReturn: StatutoryReturn): StatutoryReturn

    suspend fun findReturn(id: UUID): StatutoryReturn?

    /** Highest revision for (catalogue, return, entity, period), or null if never assembled. */
    suspend fun latestRevision(
        catalogueId: String,
        returnCode: String,
        entityId: String,
        period: ReportingPeriod,
    ): StatutoryReturn?

    suspend fun listReturns(): List<StatutoryReturn>

    /** Conditional update guarded on the expected version, so two operators cannot both win. */
    suspend fun save(statutoryReturn: StatutoryReturn, expectedVersion: Long): StatutoryReturn
}

interface StatutoryReturnMetricsPort {
    /** Publish the current number of statutory-deadline breaches (the breach gauge). */
    fun recordBreaches(count: Int)
}
