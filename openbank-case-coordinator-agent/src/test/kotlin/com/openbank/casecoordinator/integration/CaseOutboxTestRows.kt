// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.casecoordinator.integration

import com.openbank.casecoordinator.infrastructure.persistence.CaseOutboxEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus

/**
 * Production writes `case_outbox` with a native INSERT inside the workflow activity
 * (`CaseActivitiesImpl`); the conformance kits need a PENDING row per [OutboxMessage], so the
 * tests map one onto the entity and persist it through Panache instead.
 */
internal fun OutboxMessage.toCaseOutboxEntity() = CaseOutboxEntity().also {
    it.eventId = eventId
    it.synthetic = synthetic
    it.aggregateId = aggregateId
    it.eventType = eventType
    it.payload = payload
    it.status = OutboxStatus.PENDING.name
    it.attemptCount = 0
    it.createdAt = createdAt
    it.updatedAt = createdAt
}
