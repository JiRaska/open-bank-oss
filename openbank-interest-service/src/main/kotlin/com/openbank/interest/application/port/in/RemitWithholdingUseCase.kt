// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.interest.application.port.`in`

import com.openbank.interest.domain.tax.WithholdingRemittance
import io.smallrye.mutiny.Uni

/**
 * Inbound port for the withholding-tax remittance capability (ADR-0038): assemble the monthly
 * *Vyúčtování daně vybírané srážkou* and advance the paired records `RECORDED → REMITTED`.
 */
interface RemitWithholdingUseCase {

    /**
     * Assemble (or return the existing) remittance batch for the tax month `(year, month)`. Idempotent:
     * one batch per period — re-running returns the persisted batch and re-marks nothing.
     */
    fun assembleRemittance(year: Int, month: Int): Uni<WithholdingRemittance>

    /** Fetch the remittance batch for a tax month, or `null` if none has been assembled. */
    fun getRemittance(year: Int, month: Int): Uni<WithholdingRemittance?>

    /** List all assembled remittance batches (most recent first). */
    fun listRemittances(): Uni<List<WithholdingRemittance>>
}
