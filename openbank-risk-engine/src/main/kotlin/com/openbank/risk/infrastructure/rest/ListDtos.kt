// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.out.CurveSetSummary
import com.openbank.risk.application.port.out.SnapshotRunSummary
import io.quarkus.security.identity.SecurityIdentity
import java.time.Instant
import java.util.UUID

private const val DEFAULT_LIMIT = 25
private const val MAX_LIMIT = 100

/** `limit` query parameter: absent means [DEFAULT_LIMIT]; anything outside 1..[MAX_LIMIT] is a 400. */
internal fun boundedLimit(limit: Int?): Int {
    val value = limit ?: DEFAULT_LIMIT
    require(value in 1..MAX_LIMIT) { "query parameter 'limit' must be between 1 and $MAX_LIMIT" }
    return value
}

/** The caller's principal name, or null when none is set (nullable so historic runs stay nullable too). */
internal fun requestedByActor(identity: SecurityIdentity): String? =
    identity.principal?.name?.takeIf { it.isNotBlank() }

data class SnapshotRunSummaryDto(
    val id: UUID,
    val asOf: String,
    val recordedAt: Instant,
    val provenance: String,
    val status: String,
    val positionCount: Int,
    val mismatchCount: Int,
    val requestedBy: String?,
)

data class SnapshotRunListResponse(val runs: List<SnapshotRunSummaryDto>)

data class CurveSetSummaryDto(
    val id: UUID,
    val asOf: String,
    val provenance: String,
    val source: String,
    val recordedAt: Instant,
    val indices: List<String>,
)

data class CurveSetListResponse(val curveSets: List<CurveSetSummaryDto>)

fun SnapshotRunSummary.toDto() = SnapshotRunSummaryDto(
    id,
    asOf.toString(),
    recordedAt,
    provenance,
    status,
    positionCount,
    mismatchCount,
    requestedBy,
)

fun CurveSetSummary.toDto() = CurveSetSummaryDto(id, asOf.toString(), provenance, source, recordedAt, indices)
