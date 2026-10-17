// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns

import com.openbank.tax.application.port.out.ReturnDataPort
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import com.openbank.tax.infrastructure.returns.crsfatca.CrsFatcaReturnDataAdapter
import com.openbank.tax.infrastructure.returns.pension.PensionReturnDataAdapter
import jakarta.enterprise.context.ApplicationScoped

/**
 * The single [ReturnDataPort] bean, routing an assembly to the adapter owning the catalogue
 * (ADR-0336 pension returns, ADR-0340 CRS/FATCA). The concrete adapters deliberately do NOT
 * implement the port themselves: two beans of one injection-point type would fail the ArC startup
 * validation, and a catalogue with no adapter must refuse with a reason instead of an ambiguity.
 */
@ApplicationScoped
class CatalogueRoutingReturnDataPort(
    private val pension: PensionReturnDataAdapter,
    private val crsFatca: CrsFatcaReturnDataAdapter,
) : ReturnDataPort {
    override val available: Boolean = true

    override suspend fun fetch(
        catalogue: ReturnCatalogue,
        definition: ReturnDefinition,
        entityId: String,
        period: ReportingPeriod,
    ) = when (catalogue.id) {
        CrsFatcaReturnDataAdapter.CATALOGUE_ID ->
            crsFatca.fetch(catalogue, definition, entityId, period)
        PENSION_CATALOGUE_ID ->
            pension.fetch(catalogue, definition, entityId, period)
        else -> throw ReturnDataUnavailableException("no data adapter is bound to catalogue ${catalogue.id}")
    }

    companion object {
        const val PENSION_CATALOGUE_ID = "cz-pension-cnb"
    }
}
