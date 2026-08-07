// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.application.port.out

import com.openbank.consent.domain.model.Suppression
import com.openbank.libs.domain.event.DomainEvent
import java.util.UUID

interface SuppressionRepository {
    suspend fun save(suppression: Suppression, event: DomainEvent): Suppression
    suspend fun update(suppression: Suppression, event: DomainEvent): Suppression
    suspend fun findById(id: UUID): Suppression?

    /** The rows the contact-policy gate reads (ADR-0219 D3): active entries only. */
    suspend fun findActiveByParty(partyId: UUID): List<Suppression>
}
