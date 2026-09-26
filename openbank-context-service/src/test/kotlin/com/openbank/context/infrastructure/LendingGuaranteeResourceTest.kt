// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
import com.openbank.context.application.ContextQueryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class LendingGuaranteeResourceTest {
    private val queries = mockk<ContextQueryService>()
    private val source = mockk<LendingGuaranteeSourceEvidence>()
    private val identity = mockk<SecurityIdentity>()
    private val principal = mockk<JsonWebToken>()
    private val resource = LendingGuaranteeResource(queries, source, identity, Clock.systemUTC(), true)
    private val loanId = UUID.randomUUID()

    @Test
    fun `service identity and mismatched scope cannot call authorization or source`() {
        every { identity.principal } returns principal
        every { principal.name } returns "service-account-context"
        val denied = resource.approvedGuaranteesBlocking(loanId, loanId.toString(), PURPOSE)
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.getHeaderString("Cache-Control")).isEqualTo("no-store")

        assertThatThrownBy { resource.approvedGuaranteesBlocking(loanId, UUID.randomUUID().toString(), PURPOSE) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { resource.approvedGuaranteesBlocking(loanId, loanId.toString(), "OTHER") }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { queries.lendingLoanAccess<Any>(any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.read(any(), any()) }
    }

    @Test
    fun `assignment or policy denial occurs before source call`() {
        human()
        coEvery { queries.lendingLoanAccess<Any>(any(), any(), any(), any()) } throws ContextAccessDenied()
        val denied = resource.approvedGuaranteesBlocking(loanId, loanId.toString(), PURPOSE)
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.getHeaderString("Cache-Control")).isEqualTo("no-store")
        coVerify(exactly = 0) { source.read(any(), any()) }
    }

    @Test
    fun `authorization outage fails closed and route declares human roles`() {
        human()
        coEvery { queries.lendingLoanAccess<Any>(any(), any(), any(), any()) } throws ContextAuthorizationUnavailable()
        val unavailable = resource.approvedGuaranteesBlocking(loanId, loanId.toString(), PURPOSE)
        assertThat(unavailable.status).isEqualTo(503)
        assertThat(unavailable.getHeaderString("Cache-Control")).isEqualTo("no-store")
        coVerify(exactly = 0) { source.read(any(), any()) }
        assertThat(LendingGuaranteeResource::class.java.getAnnotation(RolesAllowed::class.java).value)
            .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CREDIT_RISK")
    }

    @Test
    fun `source view is disabled by default`() {
        val disabled = LendingGuaranteeResource(queries, source, identity, Clock.systemUTC(), false)
        val response = kotlinx.coroutines.runBlocking {
            disabled.approvedGuarantees(loanId, loanId.toString(), PURPOSE)
        }
        assertThat(response.status).isEqualTo(503)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store")
        coVerify(exactly = 0) { source.read(any(), any()) }
    }

    private fun human() {
        every { identity.principal } returns principal
        every { principal.name } returns "credit-risk-reviewer"
        every { principal.rawToken } returns "investigator-token"
        every { identity.roles } returns setOf("ROLE_CREDIT_RISK")
    }

    private fun LendingGuaranteeResource.approvedGuaranteesBlocking(id: UUID, case: String, purpose: String) =
        kotlinx.coroutines.runBlocking { approvedGuarantees(id, case, purpose) }

    private companion object {
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
    }
}
