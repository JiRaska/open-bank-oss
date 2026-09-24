// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutMismatch
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** `asOf` is nullable and checked in the resource: an absent field must be a 400, not a 500. */
data class CreateSnapshotRequest(val asOf: String? = null)

data class MismatchDto(
    val glAccountCode: String?,
    val currency: String,
    val ledgerNet: BigDecimal,
    val positionsNet: BigDecimal,
    val difference: BigDecimal,
)

data class SnapshotRunResponse(
    val id: UUID,
    val asOf: String,
    val recordedAt: Instant,
    val inputHash: String,
    val provenance: String,
    val status: String,
    val positionCount: Int,
    val mismatchCount: Int,
    val mismatches: List<MismatchDto>,
)

data class PositionDto(
    val kind: String,
    val glAccountCode: String?,
    val glAccountType: String?,
    val currency: String,
    val subAccountId: UUID?,
    val amount: BigDecimal,
)

data class PositionsResponse(val runId: UUID, val asOf: String, val positions: List<PositionDto>)

data class UntiedResponse(val error: String, val runId: UUID, val mismatches: List<MismatchDto>)

fun TieOutMismatch.toDto() = MismatchDto(glAccountCode, currency, ledgerNet, positionsNet, difference)

fun SnapshotRun.toResponse() = SnapshotRunResponse(
    id = id,
    asOf = asOf.toString(),
    recordedAt = recordedAt,
    inputHash = inputHash,
    provenance = provenance.wire,
    status = status.name,
    positionCount = positionCount,
    mismatchCount = mismatches.size,
    mismatches = mismatches.map { it.toDto() },
)

fun Position.toDto() = PositionDto(kind.name, glAccountCode, glAccountType, currency, subAccountId, amount)
