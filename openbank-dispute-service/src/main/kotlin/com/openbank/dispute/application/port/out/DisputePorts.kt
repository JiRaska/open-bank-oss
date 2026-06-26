// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.dispute.application.port.out

import com.openbank.dispute.domain.model.Dispute
import com.openbank.dispute.domain.model.DisputeEvidence
import com.openbank.dispute.domain.model.DisputeStatus
import com.openbank.dispute.domain.model.DisputeTimelineEvent
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Outbound persistence port for the dispute aggregate. */
interface DisputeRepository {

    fun save(dispute: Dispute): Uni<Dispute>

    fun findById(id: UUID): Uni<Dispute?>

    fun findByReference(reference: String): Uni<Dispute?>

    fun findByAccountId(accountId: UUID): Uni<List<Dispute>>

    fun findByStatus(status: DisputeStatus): Uni<List<Dispute>>

    fun update(dispute: Dispute): Uni<Dispute>
}

/** Outbound persistence port for dispute evidence artefacts. */
interface DisputeEvidenceRepository {

    fun save(evidence: DisputeEvidence): Uni<DisputeEvidence>

    fun findByDisputeId(disputeId: UUID): Uni<List<DisputeEvidence>>
}

/** Outbound persistence port for the dispute timeline (audit trail of lifecycle events). */
interface DisputeTimelineRepository {

    fun save(event: DisputeTimelineEvent): Uni<DisputeTimelineEvent>

    fun findByDisputeId(disputeId: UUID): Uni<List<DisputeTimelineEvent>>
}
