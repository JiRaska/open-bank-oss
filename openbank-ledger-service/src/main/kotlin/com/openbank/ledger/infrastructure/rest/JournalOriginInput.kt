// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.domain.model.JournalOrigin
import com.openbank.ledger.domain.model.JournalOriginKind
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import java.util.UUID

/** Optional evidence claimed by the already-authorized journal submitter, never an EventKind. */
data class JournalOriginInput(
    val kind: JournalOriginKind,
    val sourceRecordId: UUID,
    val rail: PaymentRail? = null,
    val instructionType: InstructionType? = null,
    val reversalOfTransactionId: UUID? = null,
) {
    fun toDomain(authenticatedSubmitter: String) = JournalOrigin(
        kind,
        sourceRecordId,
        authenticatedSubmitter,
        rail,
        instructionType,
        reversalOfTransactionId,
    )
}
