// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.ApprovalScaVerifier
import com.openbank.delegation.application.port.out.BusinessSigningRepository
import com.openbank.delegation.application.port.out.Transition
import com.openbank.delegation.application.port.out.TransitionContext
import com.openbank.delegation.domain.event.ApprovalExpired
import com.openbank.delegation.domain.model.ApprovalKind
import com.openbank.delegation.domain.model.ApprovalRequest
import com.openbank.delegation.domain.model.ApprovalStatus
import com.openbank.libs.domain.event.DomainEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BusinessSigningExpiryTest {
    private fun expiredPendingRequest(now: Instant, signer: UUID) = ApprovalRequest(
        id = UUID.randomUUID(),
        entityPartyId = UUID.randomUUID(),
        kind = ApprovalKind.PAYMENT,
        payload = "{}",
        payloadSha256 = "0".repeat(64),
        summary = null,
        policyVersion = 0,
        required = 2,
        eligibleSignerIds = setOf(signer),
        mustIncludeGroupId = null,
        mustIncludeSignerIds = emptySet(),
        initiatorPartyId = UUID.randomUUID(),
        signatures = emptyList(),
        status = ApprovalStatus.PENDING,
        rejection = null,
        expiresAt = now,
        createdAt = now.minusSeconds(1),
    )

    @Test
    fun `signing before and after the expiry sweep refuses without consuming SCA or writing a signature or event`(): Unit =
        runBlocking {
            val now = Instant.parse("2026-10-19T10:00:00Z")
            val signer = UUID.randomUUID()
            var stored = expiredPendingRequest(now, signer)
            val repository = mockk<BusinessSigningRepository>()
            val sca = mockk<ApprovalScaVerifier>()
            val events = mutableListOf<DomainEvent>()
            coEvery { repository.find(stored.entityPartyId, stored.id) } answers { stored }
            coEvery { repository.findExpirable(now, any()) } answers { listOf(stored) }
            coEvery { repository.transition(stored.entityPartyId, stored.id, any()) } answers {
                val decide = thirdArg<(ApprovalRequest, TransitionContext) -> Transition>()
                val transition = decide(stored, TransitionContext(0))
                events.addAll(transition.events)
                stored = transition.next
                stored
            }
            val service = BusinessSigningService(
                repository, mockk(), sca, mockk(), Clock.fixed(now, ZoneOffset.UTC),
            )
            val original = stored
            assertThatThrownBy { runBlocking { service.sign(stored.entityPartyId, stored.id, signer, UUID.randomUUID()) } }
                .isInstanceOfSatisfying(BusinessSigningException::class.java) {
                    assertThat(it.status).isEqualTo(409)
                    assertThat(it.code).isEqualTo("EXPIRED")
                }
            assertThat(stored).isEqualTo(original)
            assertThat(events).isEmpty()
            coVerify(exactly = 0) { repository.transition(any(), any(), any()) }

            assertThat(service.expireDue()).isEqualTo(1)
            assertThat(stored.status).isEqualTo(ApprovalStatus.EXPIRED)
            val swept = stored
            val expiryEvents = events.toList()
            assertThat(expiryEvents.map { it.eventType }).containsExactly(ApprovalExpired.EVENT_TYPE)
            assertThatThrownBy { runBlocking { service.sign(stored.entityPartyId, stored.id, signer, UUID.randomUUID()) } }
                .isInstanceOfSatisfying(BusinessSigningException::class.java) {
                    assertThat(it.status).isEqualTo(409)
                    assertThat(it.code).isEqualTo("EXPIRED")
                }
            assertThat(stored).isEqualTo(swept)
            assertThat(stored.signatures).isEmpty()
            assertThat(events).containsExactlyElementsOf(expiryEvents)
            coVerify(exactly = 1) { repository.transition(any(), any(), any()) }
            coVerify(exactly = 0) { sca.consumeApprovalChallenge(any(), any(), any()) }
        }
}
