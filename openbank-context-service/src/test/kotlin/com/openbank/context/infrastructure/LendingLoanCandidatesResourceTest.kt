// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
import com.openbank.context.application.ContextQueryService
import com.openbank.context.application.ContextReadResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class LendingLoanCandidatesResourceTest {
    private val queries = mockk<ContextQueryService>()
    private val candidates = mockk<LendingAssignedCandidatesRepository>()
    private val identity = mockk<SecurityIdentity>()
    private val principal = mockk<JsonWebToken>()
    private val loanId = UUID.randomUUID()
    private val resource = LendingLoanAccessResource(queries, candidates, identity, Clock.systemUTC(), true)

    @Test
    fun `rejects mismatched context and service identity before assignment read`() {
        human()
        assertThatThrownBy { request(UUID.randomUUID().toString(), PURPOSE) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { request(loanId.toString(), "OTHER") }
            .isInstanceOf(IllegalArgumentException::class.java)
        every { principal.name } returns "service-account-lending"
        assertThat(request(loanId.toString(), PURPOSE).status).isEqualTo(403)
        coVerify(exactly = 0) { queries.lendingLoanAccess<Any>(any(), any(), any(), any()) }
        coVerify(exactly = 0) { candidates.assignedCandidates(any(), any(), any()) }
    }

    @Test
    fun `root denial and outage leave candidate store unread`() {
        human()
        coEvery { queries.lendingLoanAccess<Any>(any(), any(), any(), any()) } throws ContextAccessDenied()
        assertThat(request(loanId.toString(), PURPOSE).status).isEqualTo(403)
        coEvery { queries.lendingLoanAccess<Any>(any(), any(), any(), any()) } throws ContextAuthorizationUnavailable()
        assertThat(request(loanId.toString(), PURPOSE).status).isEqualTo(503)
        coVerify(exactly = 0) { candidates.assignedCandidates(any(), any(), any()) }
    }

    @Test
    fun `disabled route does not read assignments`() {
        val disabled = LendingLoanAccessResource(queries, candidates, identity, Clock.systemUTC(), false)
        val response = runBlocking { disabled.assignedCandidates(loanId, loanId.toString(), PURPOSE) }
        assertThat(response.status).isEqualTo(503)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store")
        coVerify(exactly = 0) { candidates.assignedCandidates(any(), any(), any()) }
    }

    @Test
    fun `returned candidate IDs are supplied to disclosure audit`() {
        human()
        val another = UUID.randomUUID()
        coEvery { candidates.assignedCandidates(loanId, "lending-reviewer", any()) } returns
            LendingAssignedCandidates(listOf(another), true)
        var disclosed: ContextReadResult<Response>? = null
        coEvery { queries.lendingLoanAccess<Response>(any(), any(), any(), any()) } coAnswers {
            val result = arg<suspend () -> ContextReadResult<Response>>(3)()
            disclosed = result
            result.value
        }

        val response = request(loanId.toString(), PURPOSE)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store")
        assertThat(disclosed?.disclosure?.evidenceRefs).containsExactly("lending-loan:$another")
        assertThat(disclosed?.disclosure?.evidenceCount).isEqualTo(1)
        assertThat(disclosed?.disclosure?.truncated).isTrue()
    }

    private fun human() {
        every { identity.principal } returns principal
        every { principal.name } returns "lending-reviewer"
        every { principal.rawToken } returns "bearer-token"
        every { identity.roles } returns setOf("ROLE_CREDIT_RISK")
    }

    private fun request(caseId: String, purpose: String): Response =
        runBlocking { resource.assignedCandidates(loanId, caseId, purpose) }

    private companion object {
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
    }
}
