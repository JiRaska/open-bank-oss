// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.`in`.VerifyAccountOwnershipUseCase
import com.openbank.account.domain.model.OwnershipVerdict
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.Principal
import java.util.UUID

/** ADR-0335 D3: every verification is audited with the calling machine and the data subject. */
class AccountOwnershipResourceTest {
    @Test
    fun `a verification is audited with actor and subject`(): Unit = runBlocking {
        val published = mutableListOf<AuditEvent>()
        val useCase = mockk<VerifyAccountOwnershipUseCase>()
        val accountId = UUID.randomUUID()
        coEvery { useCase.verifyOwnership(any()) } returns
            OwnershipVerdict(owned = true, active = false, accountId = accountId)
        val resource = AccountOwnershipResource(
            useCase,
            object : AuditEventPublisher {
                override suspend fun publish(event: AuditEvent) {
                    published += event
                }
            },
        )
        resource.identity = mockk<SecurityIdentity>().also {
            every { it.principal } returns Principal { "service-account-openbank-pension" }
        }
        val party = UUID.randomUUID()

        val answer = resource.verify(OwnershipVerificationRequest(iban = "CZ6508000000192000145399", partyId = party))

        assertThat(answer).isEqualTo(OwnershipVerificationResponse(owned = true, active = false, accountId = accountId))
        val event = published.single()
        assertThat(event.actorId).isEqualTo("service-account-openbank-pension")
        assertThat(event.operation).isEqualTo("account.verifyOwnership")
        assertThat(event.resourceId).isEqualTo(party.toString())
        assertThat(event.payload).doesNotContainKey("iban")
    }
}
