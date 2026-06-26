// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sdd.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.sdd.domain.model.SddMandate
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Persistence port for the debtor mandate vault (ADR-0036 §A). */
interface SddMandateRepository {
    fun save(mandate: SddMandate): Uni<SddMandate>
    fun findById(id: UUID): Uni<SddMandate?>
    fun findByReference(creditorIdentifier: String, umr: String): Uni<SddMandate?>
    fun listForAccount(accountId: UUID): Uni<List<SddMandate>>

    /** Live mandates (ACTIVE/SUSPENDED) — the candidates the idle-expiry sweep inspects. */
    fun listLive(): Uni<List<SddMandate>>
}

/** Transactional-outbox port (ADR-0045 plumbing): libs [OutboxRepository] + a reactive in-transaction write. */
interface SddOutboxRepository : OutboxRepository {
    /** Persist a new outbox row inside an already-active Panache transaction. */
    fun append(message: OutboxMessage): Uni<Void>
}

/** Driving port used by the application layer to write to the transactional outbox. */
interface SddOutbox {
    fun append(message: OutboxMessage): Uni<Void>
}
