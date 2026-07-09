// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.application.port.`in`

import com.openbank.dispute.domain.model.*
import io.smallrye.mutiny.Uni
import java.util.UUID

interface OpenDisputeUseCase {
    fun open(request: OpenDisputeRequest): Uni<Dispute>
}

interface UpdateDisputeUseCase {
    fun update(id: UUID, request: UpdateDisputeRequest): Uni<Dispute>
    fun addEvidence(disputeId: UUID, evidence: DisputeEvidence): Uni<DisputeEvidence>
    fun withdraw(id: UUID, actor: String): Uni<Dispute>
    fun escalate(id: UUID, actor: String): Uni<Dispute>

    /**
     * Record the remediation outcome of a dispute investigation (ADR-0117 hardening §3) and, if
     * [ResolveDisputeRequest.outcome] is [RemediationOutcome.UPHELD] or [RemediationOutcome.PARTIAL],
     * emit a `dispute.remediation_requested` outbox event describing the compensating action a
     * downstream consumer (e.g. a future billing/ledger reversal flow) may act on. This service
     * does not itself post to the ledger.
     */
    fun resolve(id: UUID, request: ResolveDisputeRequest): Uni<Dispute>
}

interface GetDisputeUseCase {
    fun getDispute(id: UUID): Uni<Dispute?>
    fun getByReference(reference: String): Uni<Dispute?>
    fun listByAccount(accountId: UUID): Uni<List<Dispute>>
    fun listByStatus(status: DisputeStatus): Uni<List<Dispute>>
    fun getTimeline(disputeId: UUID): Uni<List<DisputeTimelineEvent>>
    fun getEvidence(disputeId: UUID): Uni<List<DisputeEvidence>>

    /** Walk and verify a dispute's evidence hash chain (ADR-0117 hardening §2). */
    fun verifyEvidenceChain(disputeId: UUID): Uni<EvidenceChainVerification>
}
