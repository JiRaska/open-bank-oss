// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.PrepareDisclosureCommand
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.DisclosureRepository
import com.openbank.delegation.domain.event.DisclosureSnapshotRequested
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DisclosureServiceTest {
    private val grants = mockk<DelegationRepository>()
    private val disclosures = mockk<DisclosureRepository>()
    private val now = Instant.parse("2026-09-09T12:00:00Z")
    private val service = DisclosureService(grants, disclosures, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `active document grant creates party-bound request and outbox command atomically`(): Unit = runBlocking {
        val grant = grant()
        val requestId = UUID.randomUUID()
        coEvery { disclosures.findByRequestId(requestId) } returns null
        coEvery { grants.findById(grant.id) } returns grant
        coEvery { disclosures.create(any(), any()) } answers { firstArg() }

        val result = service.prepare(PrepareDisclosureCommand(requestId, grant.id, grant.grantorPartyId))

        assertThat(result.sourceDocumentId).isEqualTo(grant.resourceId)
        coVerify {
            disclosures.create(
                match { it.requestId == requestId && it.grantorPartyId == grant.grantorPartyId },
                match<DisclosureSnapshotRequested> {
                    it.sourceDocumentId == grant.resourceId && it.expectedPartyRef == grant.grantorPartyId.toString()
                },
            )
        }
    }

    @Test
    fun `caller other than grantor is refused`(): Unit = runBlocking {
        val grant = grant()
        coEvery { disclosures.findByRequestId(any()) } returns null
        coEvery { grants.findById(grant.id) } returns grant

        assertThatThrownBy {
            runBlocking { service.prepare(PrepareDisclosureCommand(UUID.randomUUID(), grant.id, UUID.randomUUID())) }
        }.isInstanceOf(DisclosureForbiddenException::class.java)
    }

    private fun grant(): DelegationGrant {
        val time = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        return DelegationGrant(
            grantorPartyId = UUID.randomUUID(),
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.DOCUMENT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.OBJECT_READ),
            validFrom = time,
            validTo = null,
            status = DelegationStatus.ACTIVE,
            createdAt = time,
            updatedAt = time,
        )
    }
}
