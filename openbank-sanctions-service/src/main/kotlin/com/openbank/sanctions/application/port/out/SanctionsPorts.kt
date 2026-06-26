// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sanctions.application.port.out

import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import java.util.UUID

/** Outbound persistence port for the sanctions-check aggregate. */
interface SanctionsRepository {

    /** Persist check only (no outbox event). */
    suspend fun save(check: SanctionsCheck): SanctionsCheck

    /** Persist check + outbox event atomically in one transaction. */
    suspend fun saveWithEvent(check: SanctionsCheck, eventType: String): SanctionsCheck

    suspend fun findById(id: UUID): SanctionsCheck?

    suspend fun findByIdempotencyKey(key: String): SanctionsCheck?

    suspend fun findByStatus(status: SanctionsCheckStatus): List<SanctionsCheck>

    suspend fun listChecks(): List<SanctionsCheck>
}
