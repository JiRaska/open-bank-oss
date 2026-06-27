// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd

import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

object Fixtures {
    val ACCOUNT_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val CREATED_AT: Instant = Instant.parse("2026-01-01T10:00:00Z")

    fun mandate(
        scheme: SddScheme = SddScheme.CORE,
        status: MandateStatus = MandateStatus.ACTIVE,
        sequenceType: SequenceType = SequenceType.RCUR,
        b2bConfirmed: Boolean = scheme == SddScheme.CORE,
        lastCollectionDate: LocalDate? = null,
        signatureDate: LocalDate = LocalDate.parse("2026-01-01"),
    ): SddMandate = SddMandate(
        id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        accountId = ACCOUNT_ID,
        debtorIban = "CZ6508000000192000145399",
        creditorIdentifier = "DE98ZZZ09999999999",
        umr = "UMR-0001",
        scheme = scheme,
        sequenceType = sequenceType,
        creditorName = "Energie a.s.",
        debtorName = "Jan Novak",
        signatureDate = signatureDate,
        status = status,
        b2bConfirmed = b2bConfirmed,
        lastCollectionDate = lastCollectionDate,
        lastPreNotificationDate = null,
        createdAt = CREATED_AT,
    )
}
