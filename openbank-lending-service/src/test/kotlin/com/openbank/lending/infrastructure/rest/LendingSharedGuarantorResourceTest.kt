// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.GraphGuaranteeRepository
import com.openbank.lending.application.port.out.LendingAssignedCandidatesResult
import com.openbank.lending.application.port.out.LendingGraphAccessDecision
import com.openbank.lending.application.port.out.LendingGraphAccessPort
import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import com.openbank.libs.authz.Authorize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class LendingSharedGuarantorResourceTest {
    private val root = UUID.randomUUID()
    private val candidate = UUID.randomUUID()
    private val guarantor = UUID.randomUUID()
    private val now = Instant.parse("2026-09-25T12:00:00Z")
    private val bearer = "Bearer synthetic-test-token"
    private val repository = mockk<GraphGuaranteeRepository>()
    private val access = mockk<LendingGraphAccessPort>()
    private val identity = mockk<SecurityIdentity>()

    @Test
    fun `read contract accepts no caller candidate IDs`() {
        val method = LendingSharedGuarantorResource::class.java.declaredMethods
            .single { it.name == "sharedGuarantorCandidates" }
        assertThat(method.getAnnotation(GET::class.java)).isNotNull()
        assertThat(method.getAnnotation(Authorize::class.java).action).isEqualTo("lending.graph.read")
        val route = LendingSharedGuarantorResource::class.java.getAnnotation(Path::class.java).value +
            method.getAnnotation(Path::class.java).value
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val operation = contract.substringAfter("$route:").substringBefore("/api/v1/lending/applications:")
        assertThat(operation).contains("findSharedGuarantorCandidates", "    get:", "'403'", "'503'")
        assertThat(operation).doesNotContain("requestBody", "candidateLoanIds")
    }

    @Test
    fun `trusted candidates are preselected and each match is rechecked before fact query`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "reviewer" }
        val unrelated = UUID.randomUUID()
        val checked = mutableListOf<UUID>()
        coEvery { access.check(any(), bearer) } coAnswers {
            checked += firstArg<UUID>()
            LendingGraphAccessDecision.ALLOWED
        }
        coEvery { access.assignedCandidates(root, bearer) } returns
            LendingAssignedCandidatesResult.Available(listOf(candidate, unrelated), true)
        coEvery { repository.findApprovedForLoan(root, now, now, 100) } returns listOf(fact(root))
        coEvery {
            repository.findSharedCandidateLoanIds(listOf(candidate, unrelated), setOf(guarantor), now, now, 5)
        } returns listOf(candidate)
        coEvery { repository.findApprovedForLoanAndGuarantors(candidate, setOf(guarantor), now, now, 20) } coAnswers {
            assertThat(checked).containsExactly(root, candidate)
            listOf(fact(candidate))
        }

        val response = resource().sharedGuarantorCandidates(root, bearer, root.toString(), PURPOSE, null, null)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store")
        val body = response.entity as SharedGuarantorCandidateResponse
        assertThat(body.candidateTruncated).isTrue()
        assertThat(body.relatedLoansTruncated).isTrue()
        assertThat(body.relatedLoans.map { it.loanId }).containsExactly(candidate)
        assertThat(body.relatedLoans.single().guarantees.single().guarantorPartyId).isEqualTo(guarantor)
    }

    @Test
    fun `empty assigned set returns empty result without querying candidate facts`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "reviewer" }
        coEvery { access.check(root, bearer) } returns LendingGraphAccessDecision.ALLOWED
        coEvery { access.assignedCandidates(root, bearer) } returns
            LendingAssignedCandidatesResult.Available(emptyList(), false)
        coEvery { repository.findApprovedForLoan(root, now, now, 100) } returns listOf(fact(root))
        val response = resource().sharedGuarantorCandidates(root, bearer, root.toString(), PURPOSE, null, null)
        assertThat(response.status).isEqualTo(200)
        assertThat((response.entity as SharedGuarantorCandidateResponse).relatedLoans).isEmpty()
        coVerify(exactly = 0) { repository.findSharedCandidateLoanIds(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `assigned candidate without a shared guarantor is omitted`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "reviewer" }
        coEvery { access.check(root, bearer) } returns LendingGraphAccessDecision.ALLOWED
        coEvery { access.assignedCandidates(root, bearer) } returns
            LendingAssignedCandidatesResult.Available(listOf(candidate), false)
        coEvery { repository.findApprovedForLoan(root, now, now, 100) } returns listOf(fact(root))
        coEvery { repository.findSharedCandidateLoanIds(listOf(candidate), setOf(guarantor), now, now, 5) } returns
            emptyList()
        val response = resource().sharedGuarantorCandidates(root, bearer, root.toString(), PURPOSE, null, null)
        assertThat(response.status).isEqualTo(200)
        assertThat((response.entity as SharedGuarantorCandidateResponse).relatedLoans).isEmpty()
        coVerify(exactly = 0) { repository.findApprovedForLoanAndGuarantors(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `revoked match returns no body and never reads its facts`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "reviewer" }
        coEvery { access.check(root, bearer) } returns LendingGraphAccessDecision.ALLOWED
        coEvery { access.assignedCandidates(root, bearer) } returns
            LendingAssignedCandidatesResult.Available(listOf(candidate), false)
        coEvery { repository.findApprovedForLoan(root, now, now, 100) } returns listOf(fact(root))
        coEvery { repository.findSharedCandidateLoanIds(listOf(candidate), setOf(guarantor), now, now, 5) } returns
            listOf(candidate)
        coEvery { access.check(candidate, bearer) } returns LendingGraphAccessDecision.DENIED
        val response = resource().sharedGuarantorCandidates(root, bearer, root.toString(), PURPOSE, null, null)
        assertThat(response.status).isEqualTo(403)
        assertThat(response.entity).isNull()
        coVerify(exactly = 0) { repository.findApprovedForLoanAndGuarantors(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `candidate transport failure returns no body and no source query`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "reviewer" }
        coEvery { access.check(root, bearer) } returns LendingGraphAccessDecision.ALLOWED
        coEvery { access.assignedCandidates(root, bearer) } returns LendingAssignedCandidatesResult.Unavailable
        val response = resource().sharedGuarantorCandidates(root, bearer, root.toString(), PURPOSE, null, null)
        assertThat(response.status).isEqualTo(503)
        assertThat(response.entity).isNull()
        coVerify(exactly = 0) { repository.findApprovedForLoan(any(), any(), any(), any()) }
    }

    @Test
    fun `source failure returns no body`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "reviewer" }
        coEvery { access.check(root, bearer) } returns LendingGraphAccessDecision.ALLOWED
        coEvery { access.assignedCandidates(root, bearer) } returns
            LendingAssignedCandidatesResult.Available(listOf(candidate), false)
        coEvery { repository.findApprovedForLoan(root, now, now, 100) } throws IllegalStateException("database offline")
        val response = resource().sharedGuarantorCandidates(root, bearer, root.toString(), PURPOSE, null, null)
        assertThat(response.status).isEqualTo(503)
        assertThat(response.entity).isNull()
    }

    @Test
    fun `response caps related loans and facts`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "reviewer" }
        val candidates = List(5) { UUID.randomUUID() }
        coEvery { access.check(any(), bearer) } returns LendingGraphAccessDecision.ALLOWED
        coEvery { access.assignedCandidates(root, bearer) } returns
            LendingAssignedCandidatesResult.Available(candidates, false)
        coEvery { repository.findApprovedForLoan(root, now, now, 100) } returns listOf(fact(root))
        coEvery { repository.findSharedCandidateLoanIds(candidates, setOf(guarantor), now, now, 5) } returns candidates
        candidates.forEach { loan ->
            coEvery { repository.findApprovedForLoanAndGuarantors(loan, setOf(guarantor), now, now, 20) } returns
                List(21) { fact(loan) }
        }
        val response = resource().sharedGuarantorCandidates(root, bearer, root.toString(), PURPOSE, null, null)
        val body = response.entity as SharedGuarantorCandidateResponse
        assertThat(body.relatedLoans).hasSize(4)
        assertThat(body.relatedLoansTruncated).isTrue()
        assertThat(body.relatedLoans).allSatisfy {
            assertThat(it.guarantees).hasSize(20)
            assertThat(it.truncated).isTrue()
        }
        coVerify(exactly = 0) {
            repository.findApprovedForLoanAndGuarantors(candidates.last(), any(), any(), any(), any())
        }
    }

    private fun resource() = LendingSharedGuarantorResource(
        repository,
        access,
        identity,
        Clock.fixed(now, ZoneOffset.UTC),
        true,
    )

    private fun fact(loanId: UUID): GraphGuaranteeFact = GraphGuaranteeFact(
        UUID.randomUUID(),
        GraphGuaranteeProposal(
            UUID.randomUUID(), 1, null, loanId, guarantor,
            BigDecimal("100.00"), "EUR", BigDecimal("0.5"), 1,
            now.minusSeconds(3600), null, UUID.randomUUID(), "a".repeat(64),
        ),
        GraphGuaranteeStatus.APPROVED,
        "maker",
        now.minusSeconds(120),
        "checker",
        now.minusSeconds(60),
    )

    private companion object {
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
    }
}
