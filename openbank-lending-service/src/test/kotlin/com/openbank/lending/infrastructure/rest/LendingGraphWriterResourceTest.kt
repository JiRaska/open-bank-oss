// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.GraphGuaranteeIdempotencyConflict
import com.openbank.lending.application.port.out.GraphGuaranteeNotFound
import com.openbank.lending.application.port.out.GraphGuaranteeReceipt
import com.openbank.lending.application.port.out.LendingGraphProofUnavailable
import com.openbank.lending.application.usecase.GraphGuaranteeRegistrationService
import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.time.Instant
import java.util.UUID

class LendingGraphWriterResourceTest {
    private val loanId = UUID.randomUUID()
    private val proposal = GraphGuaranteeProposal(
        UUID.randomUUID(), 1, null, loanId, UUID.randomUUID(), BigDecimal("500.00"),
        "EUR", BigDecimal("0.5"), 1, Instant.parse("2026-09-25T10:00:00Z"),
        null, UUID.randomUUID(), "a".repeat(64),
    )
    private val pending = GraphGuaranteeFact(
        UUID.randomUUID(),
        proposal,
        GraphGuaranteeStatus.PENDING,
        "maker",
        Instant.parse("2026-09-25T11:00:00Z"),
        null,
        null,
    )
    private val registration = mockk<GraphGuaranteeRegistrationService>()
    private val identity = mockk<SecurityIdentity>()

    @Test
    fun `enabled route derives loan and actor and returns only a reference`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "maker" }
        coEvery { registration.proposeIdempotent(proposal, "maker", "proposal-1", any()) } returns
            GraphGuaranteeReceipt(pending.guaranteeId, 1, GraphGuaranteeStatus.PENDING)
        val response = resource().propose(loanId, "proposal-1", request())
        assertThat(response.status).isEqualTo(201)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store")
        assertThat(response.entity).isEqualTo(
            GuaranteeWriteResult(pending.guaranteeId, 1, GraphGuaranteeStatus.PENDING),
        )
        assertThat(GuaranteeWriteResult::class.java.declaredFields.map { it.name })
            .doesNotContain("proposedBy", "decidedBy", "guarantorPartyId", "sourceDocumentId")
    }

    @Test
    fun `decision refuses a guarantee from another loan before registration`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "checker" }
        coEvery {
            registration.decideIdempotent(
                loanId,
                pending.guaranteeId,
                GraphGuaranteeStatus.APPROVED,
                "checker",
                "d1",
                any(),
            )
        } throws GraphGuaranteeNotFound()
        assertThat(resource().decide(loanId, pending.guaranteeId, "d1", GuaranteeDecisionRequest("APPROVED")).status)
            .isEqualTo(404)
    }

    @Test
    fun `checker decision uses the authenticated actor and returns a minimal result`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "checker" }
        coEvery {
            registration.decideIdempotent(
                loanId,
                pending.guaranteeId,
                GraphGuaranteeStatus.APPROVED,
                "checker",
                "d1",
                any(),
            )
        } returns GraphGuaranteeReceipt(pending.guaranteeId, 1, GraphGuaranteeStatus.APPROVED)
        val response = resource().decide(loanId, pending.guaranteeId, "d1", GuaranteeDecisionRequest("APPROVED"))
        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(
            GuaranteeWriteResult(pending.guaranteeId, 1, GraphGuaranteeStatus.APPROVED),
        )
    }

    @Test
    fun `disabled route and service identity never reach source proofs or repository`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "service-account-openbank-services" }
        assertThat(resource(enabled = false).propose(loanId, "p1", request()).status).isEqualTo(503)
        assertThat(resource().propose(loanId, "p1", request()).status).isEqualTo(403)
        coVerify(exactly = 0) { registration.proposeIdempotent(any(), any(), any(), any()) }
    }

    @Test
    fun `unavailable proof fails closed without exposing source details`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "maker" }
        coEvery { registration.proposeIdempotent(proposal, "maker", "p1", any()) } throws
            LendingGraphProofUnavailable(IllegalStateException("private source detail"))
        val response = resource().propose(loanId, "p1", request())
        assertThat(response.status).isEqualTo(503)
        assertThat(response.entity).isNull()
    }

    @Test
    fun `missing or malformed retry key is rejected before registration`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "maker" }
        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking { resource().propose(loanId, null, request()) }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Idempotency-Key")
        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking { resource().propose(loanId, "has space", request()) }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Idempotency-Key")
        coVerify(exactly = 0) { registration.proposeIdempotent(any(), any(), any(), any()) }
    }

    @Test
    fun `same key with different request is a conflict`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "maker" }
        coEvery { registration.proposeIdempotent(proposal, "maker", "p1", any()) } throws
            GraphGuaranteeIdempotencyConflict()
        assertThat(resource().propose(loanId, "p1", request()).status).isEqualTo(409)
    }

    @Test
    fun `served writer methods and OpenAPI agree on the loan scoped paths`() {
        val methods = LendingGraphWriterResource::class.java.declaredMethods.filter {
            it.name in setOf("propose", "decide") && it.getAnnotation(POST::class.java) != null
        }
        assertThat(methods.map { it.name }).containsExactlyInAnyOrder("propose", "decide")
        val base = LendingGraphWriterResource::class.java.getAnnotation(Path::class.java).value
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        assertThat(contract).contains(
            "$base:",
            "$base/{guaranteeId}/decision:",
            "proposeGraphGuarantee",
            "decideGraphGuarantee",
            "Idempotency-Key",
        )
    }

    private fun resource(enabled: Boolean = true) = LendingGraphWriterResource(registration, identity, enabled)

    private fun request() = GuaranteeProposalRequest(
        proposal.contractId, proposal.revision, proposal.supersedesGuaranteeId,
        proposal.guarantorPartyId, proposal.capAmount, proposal.currency,
        proposal.coverageFraction, proposal.seniority, proposal.validFrom, proposal.validTo,
        proposal.sourceDocumentId, proposal.sourceSha256,
    )
}
