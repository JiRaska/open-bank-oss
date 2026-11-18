// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.FourEyesConsumption
import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * #16381 / #16455, at the point of effect (the request-time twin is AgentReviewGuardHttpTest):
 * the agent identity may submit only CLEAR, only while four-eyes is enforced, and the reviewer of
 * record is the HUMAN checker who approved it, never the maker identity.
 */
class SanctionsResourceV3AgentReviewTest {

    private val useCase = mockk<SanctionsUseCase>()
    private val approvals = mockk<ApprovalStore>()
    private val resource = SanctionsResourceV3(useCase).also {
        it.approvals = approvals
        it.fourEyesEnforce = true
    }

    /**
     * Sends [approvalId] the way a client does, as the X-Approval-Id request header; [consumed] is
     * the approval @Authorize consumed for this call (by default the same one, as on a real retry).
     */
    private suspend fun review(status: SanctionsCheckStatus, approvalId: String?, consumed: String? = approvalId) =
        resource.also {
            it.httpHeaders = mockk<Instance<HttpHeaders>> {
                every { isResolvable } returns true
                every { get() } returns mockk {
                    every { getRequestHeader("X-Approval-Id") } returns listOfNotNull(approvalId)
                }
            }
            it.fourEyesConsumption = mockk<Instance<FourEyesConsumption>> {
                every { isResolvable } returns true
                every { get() } returns mockk<FourEyesConsumption> { every { consumedApprovalId } returns consumed }
            }
        }.review(request(status))

    private fun caller(clientId: String, username: String) {
        resource.identity = mockk<SecurityIdentity> {
            every { principal } returns mockk<JsonWebToken> {
                every { name } returns username
                every { subject } returns "subject-$username"
                every { getClaim<Any>("azp") } returns clientId
                every { getClaim<Any>("preferred_username") } returns username
                every { getClaim<Any>("client_id") } returns null
                every { getClaim<Any>("clientId") } returns null
                every { getClaim<String>("azp") } returns clientId
                every { getClaim<String>("preferred_username") } returns username
                every { claimNames } returns setOf("azp", "preferred_username")
            }
        }
    }

    private fun asAgent() = caller("openbank-agent", "service-account-openbank-agent")

    private val checkId = UUID.randomUUID()

    private fun request(status: SanctionsCheckStatus) =
        ReviewRequest(checkId, "Name-only fuzzy match; DOB differs.", status)

    private fun approval(checker: String) = PendingApproval(
        id = "appr-1",
        action = "sanctions.clear",
        resourceId = checkId.toString(),
        makerId = "service-account-openbank-agent",
        status = ApprovalStatus.EXECUTED,
        createdAt = OffsetDateTime.parse("2026-11-17T08:00:00Z"),
        decidedBy = checker,
    )

    private fun approvedBy(checker: String) {
        coEvery { approvals.find("appr-1") } returns approval(checker)
    }

    @Test
    fun `the agent identity is refused every status except CLEAR`() {
        asAgent()
        SanctionsCheckStatus.entries.filter { it != SanctionsCheckStatus.CLEAR }.forEach { status ->
            assertThatThrownBy { runBlocking { review(status, "appr-1") } }
                .describedAs(status.name)
                .isInstanceOf(ForbiddenException::class.java)
        }
        coVerify(exactly = 0) { useCase.review(any()) }
    }

    @Test
    fun `the agent identity is refused while four-eyes is not enforced`() {
        asAgent()
        resource.fourEyesEnforce = false
        assertThatThrownBy { runBlocking { review(SanctionsCheckStatus.CLEAR, "appr-1") } }
            .isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { useCase.review(any()) }
    }

    @Test
    fun `an approved agent CLEAR is recorded under the human checker`() {
        asAgent()
        approvedBy("officer-1")
        val cmd = slot<ReviewCommand>()
        coEvery { useCase.review(capture(cmd)) } returns mockk(relaxed = true)

        runBlocking { review(SanctionsCheckStatus.CLEAR, "appr-1") }

        assertThat(cmd.captured.reviewedBy).isEqualTo("officer-1")
        assertThat(cmd.captured.note).contains("service-account-openbank-agent")
    }

    @Test
    fun `an agent CLEAR without a granted approval never executes`() {
        asAgent()
        coEvery { approvals.find(any()) } returns null
        assertThatThrownBy { runBlocking { review(SanctionsCheckStatus.CLEAR, "appr-x") } }
            .isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { useCase.review(any()) }
    }

    @Test
    fun `an approval that does not authorise exactly this agent CLEAR is refused at the point of effect`() {
        asAgent()
        val base = approval("officer-1")
        listOf(
            "other check" to base.copy(resourceId = UUID.randomUUID().toString()),
            "no check" to base.copy(resourceId = null),
            "other action" to base.copy(action = "sanctions.approval.decide"),
            "other maker" to base.copy(makerId = "service-account-openbank-services"),
            "still pending" to base.copy(status = ApprovalStatus.PENDING),
            "rejected" to base.copy(status = ApprovalStatus.REJECTED),
            "approved but not consumed by four-eyes" to base.copy(status = ApprovalStatus.APPROVED),
            "self-approved" to base.copy(decidedBy = "service-account-openbank-agent"),
            "undecided" to base.copy(decidedBy = null),
        ).forEach { (case, approval) ->
            coEvery { approvals.find("appr-1") } returns approval
            assertThatThrownBy { runBlocking { review(SanctionsCheckStatus.CLEAR, "appr-1") } }
                .describedAs(case)
                .isInstanceOf(ForbiddenException::class.java)
        }
        coVerify(exactly = 0) { useCase.review(any()) }
    }

    @Test
    fun `an approval consumed by an earlier call cannot authorise this one`() {
        asAgent()
        approvedBy("officer-1")
        // EXECUTED, right check, right maker, a different checker: everything but THIS call.
        assertThatThrownBy { runBlocking { review(SanctionsCheckStatus.CLEAR, "appr-1", consumed = null) } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { runBlocking { review(SanctionsCheckStatus.CLEAR, "appr-1", consumed = "appr-2") } }
            .isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { useCase.review(any()) }
    }

    @Test
    fun `a human reviewer is not limited and is recorded as themselves`() {
        caller("openbank-admin-ui", "officer-1")
        val cmd = slot<ReviewCommand>()
        coEvery { useCase.review(capture(cmd)) } returns mockk(relaxed = true)
        runBlocking { review(SanctionsCheckStatus.HIT, null) }
        assertThat(cmd.captured.reviewedBy).isEqualTo("officer-1")
    }
}
