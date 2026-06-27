// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.tax

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Settlement state of a monthly withholding-tax remittance batch (ADR-0038).
 *
 * v1 assembles the batch and emits `interest.withholding.remitted.v1`; the downstream consumer pays
 * the finanční úřad and (later) flips the batch to [SETTLED]. interest-service never moves the cash.
 */
enum class WithholdingRemittanceStatus {
    /** Assembled and recorded; the cash leg to the tax authority is delegated and not yet confirmed. */
    PENDING,

    /** The downstream payment/filing consumer confirmed the odvod. */
    SETTLED
}

/**
 * A monthly *Vyúčtování daně vybírané srážkou* batch (ADR-0038): the aggregate of all tax actually
 * withheld in one tax month, owed to the finanční úřad by [dueDate] (§38d odst. 3 ZDP — end of the
 * month following the withholding month). One batch per `(periodYear, periodMonth, authority)`.
 *
 * Framework-free domain aggregate. [withholdingIds] is the set of `WithholdingTax` rows folded into
 * this batch (used to stamp `remittance_id` at assembly); it is transient and not reloaded from the
 * batch row.
 */
data class WithholdingRemittance(
    val id: UUID = UUID.randomUUID(),
    val periodYear: Int,
    val periodMonth: Int,
    val authority: String = WithholdingRemittancePolicy.CZ_TAX_AUTHORITY,
    val currency: String = WithholdingRemittancePolicy.REMITTANCE_CURRENCY,
    val totalTaxAmount: BigDecimal,
    val itemCount: Int,
    val dueDate: LocalDate,
    val status: WithholdingRemittanceStatus = WithholdingRemittanceStatus.PENDING,
    val withholdingIds: List<UUID> = emptyList(),
    val createdAt: OffsetDateTime,
)
