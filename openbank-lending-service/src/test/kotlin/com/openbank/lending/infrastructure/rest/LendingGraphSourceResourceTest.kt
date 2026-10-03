// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.GraphGuaranteeRepository
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

class LendingGraphSourceResourceTest {
    private val loanId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-25T12:00:00Z")
    private val repository = mockk<GraphGuaranteeRepository>()
    private val access = mockk<LendingGraphAccessPort>()
    private val identity = mockk<SecurityIdentity>()
    private val bearer = "Bearer synthetic-test-token"

    @Test
    fun `served graph read and OpenAPI contract name the same bounded route`() {
        val method = LendingGraphSourceResource::class.java.declaredMethods.single { it.name == "approvedGuarantees" }
        assertThat(method.getAnnotation(GET::class.java)).isNotNull()
        assertThat(method.getAnnotation(Authorize::class.java).action).isEqualTo("lending.graph.read")
        val route = LendingGraphSourceResource::class.java.getAnnotation(Path::class.java).value +
            method.getAnnotation(Path::class.java).value
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val operation = contract.substringAfter("$route:").substringBefore("/api/v1/lending/applications:")
        assertThat(operation).contains("getApprovedGuaranteeHistory", "'200'", "'403'", "'503'")
        assertThat(contract).contains("maxItems: 100", "LENDING_EXPOSURE_REVIEW")
    }

    @Test
    fun `approved case read is bounded and omits reviewer identities`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "risk-reviewer" }
        coEvery { access.check(loanId, bearer) } returns LendingGraphAccessDecision.ALLOWED
        val facts = listOf(fact(1), fact(2))
        coEvery { repository.findApprovedForLoan(loanId, now, now, 1) } returns facts
        val response = resource().approvedGuarantees(
            loanId,
            bearer,
            loanId.toString(),
            PURPOSE,
            null,
            null,
            1,
        )

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store")
        val body = response.entity as ApprovedGuaranteeHistory
        assertThat(body.guarantees).hasSize(1)
        assertThat(body.truncated).isTrue()
        assertThat(body.guarantees.single().sourceSha256).isEqualTo("a".repeat(64))
        assertThat(GraphGuaranteeEvidence::class.java.declaredFields.map { it.name })
            .doesNotContain("proposedBy", "decidedBy")
    }

    @Test
    fun `denied and unavailable case checks never read source facts`(): Unit = runBlocking {
        every { identity.principal } returns Principal { "risk-reviewer" }
        coEvery { access.check(loanId, bearer) } returnsMany listOf(
            LendingGraphAccessDecision.DENIED,
            LendingGraphAccessDecision.UNAVAILABLE,
        )
        assertThat(resource().approvedGuarantees(loanId, bearer, loanId.toString(), PURPOSE, null, null, 1).status)
            .isEqualTo(403)
        assertThat(resource().approvedGuarantees(loanId, bearer, loanId.toString(), PURPOSE, null, null, 1).status)
            .isEqualTo(503)
        coVerify(exactly = 0) { repository.findApprovedForLoan(any(), any(), any(), any()) }
    }

    @Test
    fun `disabled read refuses before contacting Context`(): Unit = runBlocking {
        assertThat(
            resource(
                enabled = false,
            ).approvedGuarantees(loanId, bearer, loanId.toString(), PURPOSE, null, null, 1).status,
        )
            .isEqualTo(503)
        coVerify(exactly = 0) { access.check(any(), any()) }
    }

    private fun resource(enabled: Boolean = true) = LendingGraphSourceResource(
        repository,
        access,
        identity,
        Clock.fixed(now, ZoneOffset.UTC),
        enabled,
    )

    private fun fact(revision: Long): GraphGuaranteeFact {
        val proposal = GraphGuaranteeProposal(
            UUID.randomUUID(), revision, null, loanId, UUID.randomUUID(),
            BigDecimal("100.00"), "EUR", BigDecimal("0.5"), 1,
            now.minusSeconds(3600), null, UUID.randomUUID(), "a".repeat(64),
        )
        return GraphGuaranteeFact(
            UUID.randomUUID(),
            proposal,
            GraphGuaranteeStatus.APPROVED,
            "maker",
            now.minusSeconds(60),
            "checker",
            now,
        )
    }

    private companion object {
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
    }
}
