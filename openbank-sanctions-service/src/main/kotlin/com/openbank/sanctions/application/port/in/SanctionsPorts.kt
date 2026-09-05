// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.`in`

import com.openbank.sanctions.domain.model.*
import java.util.UUID

data class ScreenEntityCommand(
    val idempotencyKey: String,
    val entityType: EntityType,
    val name: String,
    // Nullable element ON PURPOSE (#7867): Jackson null-checks constructor parameters but not
    // collection elements, so `{"aliases": [null]}` arrives holding a null. Only a nullable
    // element type lets SanctionsService reject it with a 400 instead of an NPE-driven 500.
    val aliases: List<String?> = emptyList(),
    val dateOfBirth: String? = null,
    val nationality: String? = null,
    val identifiers: Map<String, String> = emptyMap(),
    /** Restrict screening to these list types (null/empty = all enabled lists). */
    val listTypes: List<String>? = null,
)

data class ReviewCommand(
    val checkId: UUID,
    val reviewedBy: String,
    val note: String,
    val newStatus: SanctionsCheckStatus,
)

interface SanctionsUseCase {
    suspend fun screen(cmd: ScreenEntityCommand): SanctionsCheck
    suspend fun review(cmd: ReviewCommand): SanctionsCheck
    suspend fun getById(id: UUID): SanctionsCheck?
    suspend fun listHits(): List<SanctionsCheck>
    suspend fun listPending(): List<SanctionsCheck>
    suspend fun listChecks(): List<SanctionsCheck>
}
