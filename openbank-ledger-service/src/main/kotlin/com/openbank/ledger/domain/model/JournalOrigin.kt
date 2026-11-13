// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import java.util.UUID

/** Raw origination facts, not an IR classification or proof that an economic policy was approved. */
enum class JournalOriginKind { PAYMENT_TRANSACTION, BILLING_FEE, INTEREST_CAPITALIZATION, OTHER }

/** The authenticated submitter is assigned by the ledger resource, never accepted from the wire. */
data class JournalOrigin(
    val kind: JournalOriginKind,
    val sourceRecordId: UUID,
    val authenticatedSubmitter: String,
    val rail: PaymentRail? = null,
    val instructionType: InstructionType? = null,
    val reversalOfTransactionId: UUID? = null,
)
