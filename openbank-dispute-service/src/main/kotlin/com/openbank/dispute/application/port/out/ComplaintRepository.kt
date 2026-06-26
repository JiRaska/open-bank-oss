// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.dispute.application.port.out

import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import java.util.UUID

/**
 * Outbound persistence port for the complaint aggregate. Each mutation takes an [outbox] message
 * persisted in the SAME transaction as the aggregate change (transactional outbox, ADR-0003/0050) —
 * either the complaint change and its `complaint.*` event both commit, or neither does. Reuses the
 * shared dispute outbox table/dispatcher.
 */
interface ComplaintRepository {

    fun save(complaint: Complaint, outbox: OutboxMessage): Uni<Complaint>

    fun findById(id: UUID): Uni<Complaint?>

    fun findByStatus(status: ComplaintStatus): Uni<List<Complaint>>

    fun findAll(): Uni<List<Complaint>>

    fun update(complaint: Complaint, outbox: OutboxMessage): Uni<Complaint>
}
