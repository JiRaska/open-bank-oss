// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.GraphGuaranteeReceipt
import com.openbank.lending.application.port.out.GraphGuaranteeRepository
import com.openbank.lending.application.port.out.LendingGraphProofPort
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import com.openbank.lending.domain.model.Loan
import com.openbank.libs.domain.identifiers.LoanId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class GraphGuaranteeRegistrationServiceTest {
    private val loans = mockk<LoanRepository>()
    private val proofs = mockk<LendingGraphProofPort>()
    private val guarantees = mockk<GraphGuaranteeRepository>()
    private val now = Instant.parse("2026-09-18T14:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val proposal = GraphGuaranteeProposal(
        contractId = UUID.randomUUID(),
        revision = 1,
        supersedesGuaranteeId = null,
        loanId = UUID.randomUUID(),
        guarantorPartyId = UUID.randomUUID(),
        capAmount = BigDecimal("500.00"),
        currency = "EUR",
        coverageFraction = BigDecimal("0.500000"),
        seniority = 1,
        validFrom = now,
        validTo = null,
        sourceDocumentId = UUID.randomUUID(),
        sourceSha256 = "a".repeat(64),
    )
    private val pending =
        GraphGuaranteeFact(UUID.randomUUID(), proposal, GraphGuaranteeStatus.PENDING, "maker", now, null, null)

    private fun service(enabled: Boolean = true) = GraphGuaranteeRegistrationService(
        loans,
        proofs,
        guarantees,
        clock,
        "openbank-cz",
        enabled,
    )

    private fun verifiedSources() {
        every { loans.findById(LoanId(proposal.loanId)) } returns Uni.createFrom().item(mockk<Loan>())
        coEvery { proofs.hasVerifiedGuarantorIdentity(proposal.guarantorPartyId) } returns true
        coEvery {
            proofs.matchesSignedGuarantee(
                proposal.sourceDocumentId,
                proposal.loanId,
                proposal.guarantorPartyId,
                "openbank-cz",
                proposal.sourceSha256,
            )
        } returns true
    }

    @Test
    fun `disabled writer never calls a source or database`() {
        assertThatThrownBy { runBlocking { service(enabled = false).propose(proposal, "maker") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("disabled")
        coVerify(exactly = 0) { guarantees.propose(any(), any(), any()) }
        coVerify(exactly = 0) { proofs.hasVerifiedGuarantorIdentity(any()) }
    }

    @Test
    fun `proposal needs live Party and signed loan-bound Document proofs`() {
        verifiedSources()
        coEvery { guarantees.propose(proposal, "maker", now) } returns pending
        assertThat(runBlocking { service().propose(proposal, "maker") }).isEqualTo(pending)

        coEvery { proofs.hasVerifiedGuarantorIdentity(proposal.guarantorPartyId) } returns false
        assertThatThrownBy { runBlocking { service().propose(proposal, "maker") } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("identity is not verified")
        coVerify(exactly = 1) { guarantees.propose(proposal, "maker", now) }
    }

    @Test
    fun `different checker rechecks both proofs before approval`() {
        verifiedSources()
        coEvery { guarantees.find(pending.guaranteeId) } returns pending
        val approved = pending.copy(status = GraphGuaranteeStatus.APPROVED, decidedBy = "checker", decidedAt = now)
        coEvery { guarantees.decide(pending.guaranteeId, GraphGuaranteeStatus.APPROVED, "checker", now) } returns
            approved
        assertThat(runBlocking { service().decide(pending.guaranteeId, GraphGuaranteeStatus.APPROVED, "checker") })
            .isEqualTo(approved)

        assertThatThrownBy {
            runBlocking { service().decide(pending.guaranteeId, GraphGuaranteeStatus.APPROVED, "maker") }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maker cannot decide")
        coEvery { proofs.hasVerifiedGuarantorIdentity(proposal.guarantorPartyId) } throws
            IllegalStateException("source unavailable")
        assertThatThrownBy {
            runBlocking { service().decide(pending.guaranteeId, GraphGuaranteeStatus.APPROVED, "checker") }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("source unavailable")
        coVerify(exactly = 1) { guarantees.decide(pending.guaranteeId, GraphGuaranteeStatus.APPROVED, "checker", now) }
    }

    @Test
    fun `completed proposal retry returns saved pending receipt without source access`() {
        val receipt = GraphGuaranteeReceipt(pending.guaranteeId, 1, GraphGuaranteeStatus.PENDING)
        coEvery { guarantees.findReceipt("PROPOSE", "p1", "fingerprint") } returns receipt
        assertThat(runBlocking { service().proposeIdempotent(proposal, "maker", "p1", "fingerprint") })
            .isEqualTo(receipt)
        coVerify(exactly = 0) { proofs.hasVerifiedGuarantorIdentity(any()) }
        coVerify(exactly = 0) { guarantees.proposeIdempotent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `new proposal proves sources before opening its write transaction`() {
        verifiedSources()
        coEvery { guarantees.findReceipt("PROPOSE", "p2", "fingerprint") } returns null
        val receipt = GraphGuaranteeReceipt(pending.guaranteeId, 1, GraphGuaranteeStatus.PENDING)
        coEvery { guarantees.proposeIdempotent(proposal, "maker", now, "p2", "fingerprint") } returns receipt
        assertThat(runBlocking { service().proposeIdempotent(proposal, "maker", "p2", "fingerprint") })
            .isEqualTo(receipt)
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            proofs.hasVerifiedGuarantorIdentity(proposal.guarantorPartyId)
            proofs.matchesSignedGuarantee(any(), any(), any(), any(), any())
            guarantees.proposeIdempotent(proposal, "maker", now, "p2", "fingerprint")
        }
    }
}
