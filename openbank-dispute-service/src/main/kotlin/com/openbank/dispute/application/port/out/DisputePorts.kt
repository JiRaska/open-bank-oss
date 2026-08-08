// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.application.port.out

import com.openbank.dispute.domain.model.Dispute
import com.openbank.dispute.domain.model.DisputeEvidence
import com.openbank.dispute.domain.model.DisputeStatus
import com.openbank.dispute.domain.model.DisputeTimelineEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Outbound persistence port for the dispute aggregate. */
interface DisputeRepository {

    fun save(dispute: Dispute): Uni<Dispute>

    /**
     * Persist the dispute and [outbox] in the SAME transaction (transactional outbox,
     * ADR-0003/0050) — the open path's counterpart to [update].
     *
     * A dispute committed without its `dispute.opened` event is invisible to every consumer
     * downstream, and the one that matters is ADR-0220 D1's vulnerable-customer exclusion: a
     * customer with an open dispute must stop receiving promotional surfaces, and it can only know
     * that if opening one is announced.
     */
    fun save(dispute: Dispute, outbox: List<OutboxMessage>): Uni<Dispute>

    fun findById(id: UUID): Uni<Dispute?>

    fun findByReference(reference: String): Uni<Dispute?>

    fun findByAccountId(accountId: UUID): Uni<List<Dispute>>

    fun findByStatus(status: DisputeStatus): Uni<List<Dispute>>

    fun update(dispute: Dispute): Uni<Dispute>

    /**
     * Update the dispute and persist [outbox] in the SAME transaction (transactional outbox,
     * ADR-0003/0050) — used by the resolve/remediation path so the status change and its
     * `dispute.resolved` / `dispute.remediation_requested` event commit atomically, mirroring
     * `ComplaintRepository.update`.
     */
    fun update(dispute: Dispute, outbox: List<OutboxMessage>): Uni<Dispute>
}

/** Outbound persistence port for dispute evidence artefacts. */
interface DisputeEvidenceRepository {

    fun save(evidence: DisputeEvidence): Uni<DisputeEvidence>

    fun findByDisputeId(disputeId: UUID): Uni<List<DisputeEvidence>>

    /** Evidence ordered oldest-first by chain [DisputeEvidence.sequence] — the shape [EvidenceChain] needs. */
    fun findByDisputeIdOrderedBySequence(disputeId: UUID): Uni<List<DisputeEvidence>>

    /** The current chain tail (highest [DisputeEvidence.sequence]) for a dispute, or null if none yet. */
    fun findLatestByDisputeId(disputeId: UUID): Uni<DisputeEvidence?>
}

/** Outbound persistence port for the dispute timeline (audit trail of lifecycle events). */
interface DisputeTimelineRepository {

    fun save(event: DisputeTimelineEvent): Uni<DisputeTimelineEvent>

    fun findByDisputeId(disputeId: UUID): Uni<List<DisputeTimelineEvent>>
}
