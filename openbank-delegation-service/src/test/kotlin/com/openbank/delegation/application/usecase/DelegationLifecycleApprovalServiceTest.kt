// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.DecideDelegationLifecycleCommand
import com.openbank.delegation.application.port.`in`.ProposeDelegationLifecycleCommand
import com.openbank.delegation.application.port.out.DelegationLifecycleApprovalRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.LifecycleApprovalDecision
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationLifecycleAction
import com.openbank.delegation.domain.model.DelegationLifecycleApproval
import com.openbank.delegation.domain.model.DelegationLifecycleOperation
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.governance.ProposalState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DelegationLifecycleApprovalServiceTest {
    private val approvals: DelegationLifecycleApprovalRepository = mockk()
    private val delegations: DelegationRepository = mockk()
    private val now = Instant.parse("2026-09-02T08:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var service: DelegationLifecycleApprovalService

    private val grant = DelegationGrant(
        id = UUID.randomUUID(),
        grantorPartyId = UUID.randomUUID(),
        granteePartyId = UUID.randomUUID(),
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.randomUUID(),
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        approvalPolicy = ApprovalPolicy.SOLO,
        validFrom = OffsetDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC),
        validTo = null,
        status = DelegationStatus.ACTIVE,
        createdAt = OffsetDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC),
        updatedAt = OffsetDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC),
    )

    @BeforeEach
    fun setUp() {
        service = DelegationLifecycleApprovalService(approvals, delegations, clock)
        coEvery { delegations.findById(grant.id) } returns grant
        coEvery { approvals.findByRequestKey(any()) } returns null
    }

    @Test
    fun `maker cannot reject their own proposal`(): Unit = runBlocking {
        val approval = proposed("maker")
        coEvery { approvals.decideAtomically(approval.id, any()) } coAnswers {
            arg<(DelegationLifecycleApproval) -> LifecycleApprovalDecision>(1)
                .invoke(approval).approval
        }

        assertThatThrownBy {
            runBlocking {
                service.decide(DecideDelegationLifecycleCommand(approval.id, false, "maker", "checked"))
            }
        }.isInstanceOf(SelfApprovalNotAllowedException::class.java)
    }

    @Test
    fun `approval execution stays fail closed before the revision safe lifecycle seam`(): Unit = runBlocking {
        val approval = proposed("maker")
        coEvery { approvals.decideAtomically(approval.id, any()) } coAnswers {
            arg<(DelegationLifecycleApproval) -> LifecycleApprovalDecision>(1)
                .invoke(approval).approval
        }

        assertThatThrownBy {
            runBlocking {
                service.decide(
                    DecideDelegationLifecycleCommand(approval.id, true, "checker", "evidence verified"),
                )
            }
        }.isInstanceOf(DelegationLifecycleApprovalConflict::class.java)
            .hasMessageContaining("revision-safe")
    }

    @Test
    fun `rejection records both immutable reasons`(): Unit = runBlocking {
        val approval = proposed("maker")
        var plan: LifecycleApprovalDecision? = null
        coEvery { approvals.decideAtomically(approval.id, any()) } coAnswers {
            plan = arg<(DelegationLifecycleApproval) -> LifecycleApprovalDecision>(1)
                .invoke(approval)
            plan!!.approval
        }

        val result = service.decide(
            DecideDelegationLifecycleCommand(approval.id, false, "checker", "evidence insufficient"),
        )

        assertThat(plan).isInstanceOf(LifecycleApprovalDecision.Rejected::class.java)
        assertThat(result.state).isEqualTo(ProposalState.REJECTED)
        assertThat(result.action.reason).isEqualTo("fraud signal 42")
        assertThat(result.decisionReason).isEqualTo("evidence insufficient")
    }

    @Test
    fun `an identical terminal rejection is a side effect free replay`(): Unit = runBlocking {
        val terminal = proposed("maker").copy(
            state = ProposalState.REJECTED,
            decidedBy = "checker",
            decidedAt = now,
            decisionReason = "evidence verified",
        )
        var plan: LifecycleApprovalDecision? = null
        coEvery { approvals.decideAtomically(terminal.id, any()) } coAnswers {
            plan = arg<(DelegationLifecycleApproval) -> LifecycleApprovalDecision>(1)
                .invoke(terminal)
            plan!!.approval
        }

        service.decide(DecideDelegationLifecycleCommand(terminal.id, false, "checker", "evidence verified"))

        assertThat(plan).isInstanceOf(LifecycleApprovalDecision.Replayed::class.java)
    }

    @Test
    fun `reusing a request id for different content is refused`(): Unit = runBlocking {
        val existing = proposed("maker")
        coEvery { approvals.findByRequestKey(existing.requestKey) } returns existing

        assertThatThrownBy {
            runBlocking {
                service.propose(
                    ProposeDelegationLifecycleCommand(
                        grant.id,
                        DelegationLifecycleOperation.REVOKE,
                        "different action",
                        "maker",
                        existing.requestKey,
                    ),
                )
            }
        }.isInstanceOf(DelegationLifecycleApprovalConflict::class.java)
    }

    @Test
    fun `identical request id replay returns evidence even after target state changes`(): Unit = runBlocking {
        val existing = proposed("maker")
        coEvery { approvals.findByRequestKey(existing.requestKey) } returns existing
        coEvery { delegations.findById(grant.id) } returns grant.copy(status = DelegationStatus.SUSPENDED)

        val replay = service.propose(
            ProposeDelegationLifecycleCommand(
                grant.id,
                DelegationLifecycleOperation.SUSPEND,
                existing.action.reason,
                "maker",
                existing.requestKey,
            ),
        )

        assertThat(replay).isEqualTo(existing)
    }

    @Test
    fun `reinstate cannot be proposed for an active grant`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking {
                service.propose(
                    ProposeDelegationLifecycleCommand(
                        grant.id,
                        DelegationLifecycleOperation.REINSTATE,
                        "restore after review",
                        "maker",
                        "request-2",
                    ),
                )
            }
        }.isInstanceOf(DelegationLifecycleApprovalConflict::class.java)
    }

    private fun proposed(maker: String) = DelegationLifecycleApproval(
        id = UUID.randomUUID(),
        action = DelegationLifecycleAction(grant.id, DelegationLifecycleOperation.SUSPEND, "fraud signal 42"),
        requestKey = "request-1",
        proposedBy = maker,
        proposedAt = now.minusSeconds(60),
    )
}
