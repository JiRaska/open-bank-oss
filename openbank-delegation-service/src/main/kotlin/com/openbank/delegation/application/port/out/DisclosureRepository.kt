// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.event.DisclosureSnapshotRequested
import com.openbank.delegation.domain.model.Disclosure
import java.time.Instant
import java.util.UUID

interface DisclosureRepository {
    suspend fun create(disclosure: Disclosure, event: DisclosureSnapshotRequested): Disclosure?
    suspend fun findById(id: UUID): Disclosure?
    suspend fun findByRequestId(requestId: UUID): Disclosure?
    suspend fun markReady(
        requestId: UUID,
        snapshotId: UUID,
        sourceDocumentId: UUID,
        sourceSha256: String,
        snapshotSha256: String,
        sizeBytes: Long,
        occurredAt: Instant,
    )
    suspend fun markRejected(requestId: UUID, reason: String, occurredAt: Instant)
}
