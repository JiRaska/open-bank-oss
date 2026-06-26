// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.RegisterIdentityCommand
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.domain.model.AmlRiskScore
import com.openbank.pid.domain.model.ContactAttributes
import com.openbank.pid.domain.model.CoreAttributes
import com.openbank.pid.domain.model.ExternalId
import com.openbank.pid.domain.model.ExternalIdType
import com.openbank.pid.domain.model.KycAttributes
import com.openbank.pid.domain.model.KycLevel
import com.openbank.pid.domain.model.Party
import com.openbank.pid.domain.model.PartyStatus
import com.openbank.pid.domain.model.PartyType
import com.openbank.pid.domain.model.VerificationSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class IdentityRegistrationServiceTest {

    private val repo = mockk<PartyRepository>()
    private val pepper = "00112233445566778899aabbccddeeff"

    private val testClock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private fun service(withPepper: Boolean = true) =
        IdentityRegistrationService(repo, if (withPepper) Optional.of(pepper) else Optional.empty(), testClock)

    private fun command(partyId: UUID, rc: String? = "490101123", sub: String? = "sub-1", eudiSub: String? = null) =
        RegisterIdentityCommand(
            partyId = partyId,
            partyType = PartyType.NATURAL_PERSON,
            givenName = "Jan",
            familyName = "Novák",
            birthdate = LocalDate.of(1949, 1, 1),
            birthNumberRaw = rc,
            keycloakSub = sub,
            eudiPidSubVerified = eudiSub,
        )

    private fun indexParty(id: UUID, externalIds: List<ExternalId> = emptyList()): Party {
        val now = OffsetDateTime.parse("2025-01-01T00:00:00Z")
        return Party(
            id = id,
            partyType = PartyType.NATURAL_PERSON,
            status = PartyStatus.ACTIVE,
            externalIds = externalIds,
            coreAttributes = CoreAttributes(
                "Jan", "Novák", LocalDate.of(1949, 1, 1), null, null, null,
                emptyList(), emptyList(), VerificationSource.API_UPLOAD, now,
            ),
            addressAttributes = null,
            contactAttributes = ContactAttributes(null, null, null, null),
            kycAttributes = KycAttributes(KycLevel.NONE, null, null, AmlRiskScore.LOW, false, false, null, null),
            relationships = emptyList(),
            caseLifecycle = null,
            createdAt = now, updatedAt = now, version = 0,
        )
    }

    @Test
    fun `a new identity is saved with the RC blind index and the keycloak sub`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findByExternalId(any(), any()) } returns null
        coEvery { repo.findById(id) } returns null
        val saved = slot<Party>()
        coEvery { repo.save(capture(saved)) } answers { saved.captured }

        service().register(command(id))

        val types = saved.captured.externalIds.map { it.type }
        assertThat(types).contains(ExternalIdType.BIRTH_NUMBER, ExternalIdType.KEYCLOAK_ID)
        assertThat(saved.captured.id).isEqualTo(id)
        assertThat(saved.captured.externalIds.single { it.type == ExternalIdType.BIRTH_NUMBER }.value).isNotBlank()
    }

    @Test
    fun `re-registering the same party id merges and updates instead of saving`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findByExternalId(any(), any()) } returns null
        coEvery { repo.findById(id) } returns indexParty(id)
        val updated = slot<Party>()
        coEvery { repo.update(capture(updated)) } answers { updated.captured }

        service().register(command(id))

        coVerify(exactly = 0) { repo.save(any()) }
        assertThat(updated.captured.version).isEqualTo(1)
        assertThat(updated.captured.externalIds.map { it.type }).contains(ExternalIdType.BIRTH_NUMBER)
    }

    @Test
    fun `a blind index already bound to another party is rejected`() {
        val id = UUID.randomUUID()
        val other = indexParty(UUID.randomUUID())
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, any()) } returns other
        coEvery { repo.findByExternalId(ExternalIdType.KEYCLOAK_ID, any()) } returns null

        assertThatThrownBy { runBlocking { service().register(command(id)) } }
            .isInstanceOf(PartyAlreadyExistsException::class.java)
    }

    @Test
    fun `without a pepper the record registers tier-2 only (no blind index)`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findByExternalId(any(), any()) } returns null
        coEvery { repo.findById(id) } returns null
        val saved = slot<Party>()
        coEvery { repo.save(capture(saved)) } answers { saved.captured }

        service(withPepper = false).register(command(id))

        assertThat(saved.captured.externalIds.map { it.type })
            .containsExactly(ExternalIdType.KEYCLOAK_ID)
    }

    @Test
    fun `a verified EUDI PID is stored as an EUDI_PID_SUB blind index (never the raw sub)`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findByExternalId(any(), any()) } returns null
        coEvery { repo.findById(id) } returns null
        val saved = slot<Party>()
        coEvery { repo.save(capture(saved)) } answers { saved.captured }

        service().register(command(id, eudiSub = "sub:CZ-PID-0001"))

        val eudi = saved.captured.externalIds.single { it.type == ExternalIdType.EUDI_PID_SUB }
        assertThat(eudi.value).isNotBlank().isNotEqualTo("sub:CZ-PID-0001") // blind-indexed, never raw
    }

    @Test
    fun `without a pepper an EUDI sub is skipped (degrades, does not crash)`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findByExternalId(any(), any()) } returns null
        coEvery { repo.findById(id) } returns null
        val saved = slot<Party>()
        coEvery { repo.save(capture(saved)) } answers { saved.captured }

        service(withPepper = false).register(command(id, eudiSub = "sub:CZ-PID-0001"))

        assertThat(saved.captured.externalIds.map { it.type })
            .containsExactly(ExternalIdType.KEYCLOAK_ID)
    }

    @Test
    fun `an EUDI PID already bound to another party is rejected`() {
        val id = UUID.randomUUID()
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, any()) } returns null
        coEvery { repo.findByExternalId(ExternalIdType.KEYCLOAK_ID, any()) } returns null
        coEvery { repo.findByExternalId(ExternalIdType.EUDI_PID_SUB, any()) } returns indexParty(UUID.randomUUID())

        assertThatThrownBy { runBlocking { service().register(command(id, eudiSub = "sub:CZ-PID-0001")) } }
            .isInstanceOf(PartyAlreadyExistsException::class.java)
    }
}
