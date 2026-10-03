// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.messaging

import com.openbank.kyb.domain.model.UboObservation
import com.openbank.kyb.infrastructure.persistence.repository.KybJson
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.util.UUID

/** Reference-only v1 wire shape. Owner attributes remain in KYB and may never be added here. */
internal data class UboObservationReference(
    val schemaVersion: Int,
    val eventType: String,
    val caseId: UUID,
    val observationId: UUID,
    val revision: Long,
    val sourceSha256: String,
) {
    fun toOutboxMessage(recordedAt: Instant) = OutboxMessage(
        aggregateId = caseId,
        eventType = eventType,
        payload = KybJson.mapper.writeValueAsString(this),
        createdAt = recordedAt,
    )

    companion object {
        const val EVENT_TYPE = "KybUboObservationRecorded"

        fun from(observation: UboObservation) = UboObservationReference(
            schemaVersion = 1,
            eventType = EVENT_TYPE,
            caseId = observation.caseId,
            observationId = observation.id,
            revision = observation.revision,
            sourceSha256 = observation.sourceSha256,
        )
    }
}

/** Same minimized wire fields, but a distinct event type that never carries a reason or identity. */
internal data class UboObservationRestrictionReference(
    val schemaVersion: Int,
    val eventType: String,
    val caseId: UUID,
    val observationId: UUID,
    val revision: Long,
    val sourceSha256: String,
) {
    fun toOutboxMessage(restrictedAt: Instant) = OutboxMessage(
        aggregateId = caseId,
        eventType = eventType,
        payload = KybJson.mapper.writeValueAsString(this),
        createdAt = restrictedAt,
    )

    companion object {
        const val EVENT_TYPE = "KybUboObservationRestricted"

        fun from(caseId: UUID, observationId: UUID, revision: Long, sourceSha256: String) =
            UboObservationRestrictionReference(1, EVENT_TYPE, caseId, observationId, revision, sourceSha256)
    }
}
