// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.reporting.ParticipantPeriodAggregates
import java.time.LocalDate
import java.util.UUID

data class ParticipantSnapshot(
    val snapshotId: UUID,
    val companyId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val revision: Int,
    val payloadSchemaVersion: String,
    val sha256: String,
    val payloadJson: String,
)

interface ParticipantSnapshotRepository {
    suspend fun receipt(scopeHash: String, requestHash: String): ParticipantSnapshot?
    suspend fun byId(snapshotId: UUID, companyId: UUID): ParticipantSnapshot?

    @Suppress("LongParameterList")
    suspend fun capture(
        scopeHash: String,
        requestHash: String,
        companyId: UUID,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        schemaVersion: String,
        payloadJson: String,
        sha256: String,
    ): ParticipantSnapshot
}

interface ParticipantSnapshotSerializer {
    fun canonicalJson(aggregates: ParticipantPeriodAggregates): String
}
