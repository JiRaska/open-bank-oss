// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.out

import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import java.util.UUID

/** Outbound persistence port for the sanctions-check aggregate. */
interface SanctionsRepository {

    /** Persist check only (no outbox event). */
    suspend fun save(check: SanctionsCheck): SanctionsCheck

    /**
     * INSERT a NEW check + its outbox event atomically in one transaction.
     *
     * Insert-only by design. The aggregate's `@Id` is application-assigned, so this cannot be
     * reused to update an existing check — see [updateWithEvent], and the split's rationale in
     * `SanctionsRepositoryImpl`.
     */
    suspend fun saveWithEvent(check: SanctionsCheck, eventType: String): SanctionsCheck

    /**
     * UPDATE an existing check + emit its outbox event atomically in one transaction.
     *
     * Separate from [saveWithEvent] because the two lifecycles need different Hibernate
     * operations on an application-assigned `@Id`, and because they must fail differently on a
     * primary-key collision — the insert path treats one as a real defect worth propagating.
     */
    suspend fun updateWithEvent(check: SanctionsCheck, eventType: String): SanctionsCheck

    suspend fun findById(id: UUID): SanctionsCheck?

    suspend fun findByIdempotencyKey(key: String): SanctionsCheck?

    suspend fun findByStatus(status: SanctionsCheckStatus): List<SanctionsCheck>

    suspend fun listChecks(): List<SanctionsCheck>
}
