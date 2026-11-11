// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.application.port.out

import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class CzCreditPolicyRevision(
    val id: UUID,
    val revision: Int,
    val effectiveFrom: LocalDate,
    val payload: String,
    val contentHash: String,
    val state: ProposalState,
    val proposedBy: String,
    val proposedAt: OffsetDateTime,
    val decidedBy: String? = null,
    val decidedAt: OffsetDateTime? = null,
    val decisionReason: String? = null,
)

interface CzCreditPolicyStore {
    fun propose(row: CzCreditPolicyRevision): Uni<CzCreditPolicyRevision>
    fun find(id: UUID): Uni<CzCreditPolicyRevision?>
    fun pending(): Uni<List<CzCreditPolicyRevision>>
    fun active(asOf: LocalDate): Uni<CzCreditPolicyRevision?>
    fun decide(id: UUID, decision: Proposal<PolicyBundle>): Uni<Int>
}
