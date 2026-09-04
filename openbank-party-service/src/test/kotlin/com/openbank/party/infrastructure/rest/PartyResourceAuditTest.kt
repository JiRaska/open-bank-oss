// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.party.application.port.`in`.ErasePartyCommand
import com.openbank.party.application.port.`in`.UpdateMarketingConsentCommand
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyGdprExport
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR-0118 / ADR-0086: asserts that both GDPR endpoints emit an [AuditEvent] onto the libs
 * audit pipeline — Art. 17 erasure and Art. 15 subject-access read both belong in the Art. 30
 * records-of-processing trail (who accessed/erased which subject, when).
 */
class PartyResourceAuditTest {

    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val actorSub = "00000000-0000-0000-0000-000000000099"
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    private fun resource(events: MutableList<AuditEvent>): PartyResource {
        val publisher = mockk<AuditEventPublisher>().also {
            coEvery { it.publish(capture(events)) } returns Unit
        }
        val token = mockk<JsonWebToken>().also { every { it.subject } returns actorSub }
        val tokenInstance = mockk<Instance<JsonWebToken>>().also {
            every { it.isResolvable } returns true
            every { it.get() } returns token
        }
        val identity = mockk<io.quarkus.security.identity.SecurityIdentity>().also {
            every { it.hasRole("ROLE_ADMIN") } returns true
            every { it.hasRole("ROLE_DPO") } returns false
        }
        return PartyResource().apply {
            partyUseCase = mockk(relaxed = true)
            flags = mockk(relaxed = true)
            jwtInstance = tokenInstance
            securityIdentity = identity
            auditPublisher = publisher
        }
    }

    private fun sampleParty() = Party(
        id = partyId,
        partyType = PartyType.INDIVIDUAL,
        status = PartyStatus.ACTIVE,
        legalName = "Jan Novak",
        tradingName = null,
        dateOfBirth = "1985-06-15",
        nationality = "CZE",
        taxId = null,
        registrationNumber = null,
        email = "jan.novak@openbank.test",
        phone = "+420777111222",
        address = null,
        kycStatus = KycStatus.APPROVED,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `eraseParty emits a party_erase audit event with the actor and subject`(): Unit = runBlocking {
        val events = mutableListOf<AuditEvent>()
        val res = resource(events)
        coJustRun { res.partyUseCase.eraseParty(ErasePartyCommand(partyId)) }

        res.eraseParty(partyId)

        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("party.erase")
            assertThat(e.actorId).isEqualTo(actorSub)
            assertThat(e.actorType).isEqualTo("HUMAN")
            assertThat(e.resourceType).isEqualTo("party")
            assertThat(e.resourceId).isEqualTo(partyId.toString())
            assertThat(e.result).isEqualTo(AuditResult.SUCCESS)
            assertThat(e.payload["gdpr_article"]).isEqualTo("17")
        })
        coVerify(exactly = 1) { res.partyUseCase.eraseParty(ErasePartyCommand(partyId)) }
    }

    @Test
    fun `exportPartyGdpr emits a party_gdpr-export audit event with the actor and subject`(): Unit = runBlocking {
        val events = mutableListOf<AuditEvent>()
        val res = resource(events)
        coEvery { res.partyUseCase.exportPartyData(partyId) } returns
            PartyGdprExport(sampleParty(), emptyList(), now)

        res.exportPartyGdpr(partyId)

        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("party.gdpr-export")
            assertThat(e.actorId).isEqualTo(actorSub)
            assertThat(e.actorType).isEqualTo("HUMAN")
            assertThat(e.resourceType).isEqualTo("party")
            assertThat(e.resourceId).isEqualTo(partyId.toString())
            assertThat(e.result).isEqualTo(AuditResult.SUCCESS)
            assertThat(e.payload["gdpr_article"]).isEqualTo("15")
        })
    }

    @Test
    fun `exportPartyGdpr admits a DPO caller and audits the access`(): Unit = runBlocking {
        // #8495: ROLE_DPO is now a real realm role, so pin the branch the other way than the
        // fixture above — a caller holding ONLY ROLE_DPO (no ROLE_ADMIN, no self-JWT) must be
        // admitted. Before the role was defined in the realm this branch was unreachable and
        // the suite stayed green precisely because nothing could hold it.
        val events = mutableListOf<AuditEvent>()
        val res = resource(events).apply {
            securityIdentity = mockk<io.quarkus.security.identity.SecurityIdentity>().also {
                every { it.hasRole("ROLE_ADMIN") } returns false
                every { it.hasRole("ROLE_DPO") } returns true
            }
        }
        coEvery { res.partyUseCase.getPartyKeycloakSub(partyId) } returns "somebody-else"
        coEvery { res.partyUseCase.exportPartyData(partyId) } returns
            PartyGdprExport(sampleParty(), emptyList(), now)

        val response = res.exportPartyGdpr(partyId)

        assertThat(response.status).isEqualTo(200)
        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("party.gdpr-export")
            assertThat(e.result).isEqualTo(AuditResult.SUCCESS)
            assertThat(e.payload["gdpr_article"]).isEqualTo("15")
        })
    }

    @Test
    fun `exportPartyGdpr does not emit an audit event when the subject fetch fails`(): Unit = runBlocking {
        val events = mutableListOf<AuditEvent>()
        val res = resource(events)
        coEvery { res.partyUseCase.exportPartyData(partyId) } throws RuntimeException("not found")

        runCatching { res.exportPartyGdpr(partyId) }

        assertThat(events).isEmpty()
    }

    @Test
    fun `updateConsent emits an event with both the before and after marketing-consent values`(): Unit = runBlocking {
        val events = mutableListOf<AuditEvent>()
        val res = resource(events)
        coEvery { res.partyUseCase.getParty(partyId) } returns sampleParty().copy(consentMarketing = true)
        coEvery { res.partyUseCase.updateMarketingConsent(UpdateMarketingConsentCommand(partyId, false)) } returns
            sampleParty().copy(consentMarketing = false, consentMarketingUpdatedAt = now)

        res.updateConsent(partyId, UpdateConsentRequest(marketingConsent = false))

        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("party.consent.marketing-updated")
            assertThat(e.actorId).isEqualTo(actorSub)
            // The actor here is always the customer-edge M2M identity, never the customer directly.
            assertThat(e.actorType).isEqualTo("SERVICE")
            assertThat(e.resourceType).isEqualTo("party")
            assertThat(e.resourceId).isEqualTo(partyId.toString())
            assertThat(e.result).isEqualTo(AuditResult.SUCCESS)
            assertThat(e.payload["marketingConsentBefore"]).isEqualTo("true")
            assertThat(e.payload["marketingConsentAfter"]).isEqualTo("false")
        })
    }

    @Test
    fun `updateConsent records a null before-value when marketing consent was never set`(): Unit = runBlocking {
        val events = mutableListOf<AuditEvent>()
        val res = resource(events)
        coEvery { res.partyUseCase.getParty(partyId) } returns sampleParty()
        coEvery { res.partyUseCase.updateMarketingConsent(any()) } returns
            sampleParty().copy(consentMarketing = true, consentMarketingUpdatedAt = now)

        res.updateConsent(partyId, UpdateConsentRequest(marketingConsent = true))

        assertThat(events.single().payload["marketingConsentBefore"]).isEqualTo("null")
    }
}
