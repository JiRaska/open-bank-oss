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

data class CzCreditPolicyCommand(val keyDigest: String, val requestHash: String)

data class CzCreditPolicyWrite(val revision: CzCreditPolicyRevision, val replayed: Boolean)

interface CzCreditPolicyStore {
    fun propose(
        command: CzCreditPolicyCommand,
        id: UUID,
        create: () -> CzCreditPolicyRevision,
    ): Uni<CzCreditPolicyWrite>

    fun decide(
        command: CzCreditPolicyCommand,
        id: UUID,
        approve: Boolean,
        decide: (CzCreditPolicyRevision) -> Proposal<PolicyBundle>,
    ): Uni<CzCreditPolicyWrite>

    fun pending(): Uni<List<CzCreditPolicyRevision>>
    fun active(asOf: LocalDate): Uni<CzCreditPolicyRevision?>
}
