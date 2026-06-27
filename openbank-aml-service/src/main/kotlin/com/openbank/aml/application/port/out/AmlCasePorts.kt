// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.out

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.util.UUID

/**
 * Outbound persistence port for the AML case aggregate.
 *
 * [save] and [update] persist the aggregate **and** its domain event ([event]) in a single
 * transaction (the transactional-outbox pattern, ADR-0050): either both the case row and the outbox
 * row commit, or neither does. The dispatcher then drains the outbox to Kafka asynchronously, so a
 * crash between the DB commit and the Kafka publish can never lose — or double-emit — an event.
 */
interface AmlCaseRepository {

    suspend fun save(amlCase: AmlCase, event: OutboxMessage): AmlCase

    suspend fun findById(caseId: UUID): AmlCase?

    suspend fun findByIdempotencyKey(idempotencyKey: String): AmlCase?

    suspend fun list(
        status: AmlCaseStatus?,
        partyId: UUID?,
        screeningType: ScreeningType?,
        limit: Int,
        offset: Int,
    ): List<AmlCase>

    suspend fun update(amlCase: AmlCase, event: OutboxMessage): AmlCase
}
