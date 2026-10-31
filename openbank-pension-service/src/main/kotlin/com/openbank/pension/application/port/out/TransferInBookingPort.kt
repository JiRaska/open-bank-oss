// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The seam from transfer-in (S2) to the contribution ledger (S3), in-process: transferred money is
 * booked as a TRANSFER_IN contribution so the tax year reports it apart from new contributions.
 * It places NO subscription — the transfer completion already bought the units. Idempotent per
 * [transferId].
 */
fun interface TransferInBookingPort {
    suspend fun book(contractId: UUID, transferId: UUID, amount: BigDecimal, currency: String, valueDate: LocalDate)
}
