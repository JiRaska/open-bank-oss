// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
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
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class LendingSharedGuarantorResourceTest {
    private val root = UUID.randomUUID()
    private val related = UUID.randomUUID()
    private val guarantee = UUID.randomUUID()
    private val document = UUID.randomUUID()
    private val now = Instant.parse("2026-09-25T12:00:00Z")
    private val queries = mockk<ContextQueryService>()
    private val source = mockk<LendingGuaranteeSourceEvidence>()
    private val identity = mockk<SecurityIdentity>()
    private val principal = mockk<JsonWebToken>()

    @Test
    fun `root denial avoids source and returns no body`() {
        human()
        coEvery { queries.lendingLoanAccess<Response>(any(), any(), any(), any()) } throws ContextAccessDenied()
        val response = request()
        assertThat(response.status).isEqualTo(403)
        assertThat(response.entity).isNull()
        coVerify(exactly = 0) { source.readShared(any(), any()) }
    }

    @Test
    fun `related loan revocation suppresses whole response`() {
        human()
        coEvery { source.readShared(root, "Bearer token") } returns history()
        coEvery { queries.lendingLoanAccess<Response>(root.toString(), any(), any(), any()) } coAnswers {
            arg<suspend () -> ContextReadResult<Response>>(3)().value
        }
        coEvery { queries.lendingLoanAccess<Unit>(related.toString(), any(), any(), any()) } throws
            ContextAccessDenied()
        val response = request()
        assertThat(response.status).isEqualTo(403)
        assertThat(response.entity).isNull()
    }

    @Test
    fun `all related loans rechecked before evidence disclosure`() {
        human()
        coEvery { source.readShared(root, "Bearer token") } returns history()
        var disclosure: ContextReadResult<Response>? = null
        var childChecked = false
        coEvery { queries.lendingLoanAccess<Unit>(related.toString(), any(), any(), any()) } coAnswers {
            childChecked = true
            arg<suspend () -> ContextReadResult<Unit>>(3)().value
        }
        coEvery { queries.lendingLoanAccess<Response>(root.toString(), any(), any(), any()) } coAnswers {
            val result = arg<suspend () -> ContextReadResult<Response>>(3)()
            assertThat(childChecked).isTrue()
            disclosure = result
            result.value
        }
        val response = request()
        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store")
        assertThat(disclosure?.disclosure?.evidenceRefs).containsExactly(
            "lending-loan:$related",
            "lending-guarantee:$guarantee:document:$document",
        )
    }

    private fun history() = LendingSharedGuarantorHistory(
        root,
        now,
        now,
        false,
        false,
        listOf(
            LendingRelatedLoanEvidence(
                related,
                listOf(
                    LendingGuaranteeEvidence(
                        guarantee, UUID.randomUUID(), 1, null, UUID.randomUUID(), BigDecimal.TEN,
                        "EUR", BigDecimal.ONE, 1, now.minusSeconds(60), null, document,
                        "a".repeat(64), now.minusSeconds(30),
                    ),
                ),
                false,
            ),
        ),
    )

    private fun human() {
        every { identity.principal } returns principal
        every { principal.name } returns "reviewer"
        every { principal.rawToken } returns "token"
        every { identity.roles } returns setOf("ROLE_CREDIT_RISK")
    }

    private fun request(): Response = runBlocking {
        LendingSharedGuarantorResource(queries, source, identity, Clock.fixed(now, ZoneOffset.UTC), true)
            .sharedGuarantors(root, root.toString(), "LENDING_EXPOSURE_REVIEW")
    }
}
