// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.ErasureOutcome
import com.openbank.pid.application.port.out.ApplicantCaseKeys
import com.openbank.pid.application.port.out.PartyAnonymisation
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.application.port.out.VerificationCaseRepository
import com.openbank.pid.domain.model.ExternalId
import com.openbank.pid.domain.model.ExternalIdType
import com.openbank.pid.domain.model.Party
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class PartyErasureServiceTest {

    private val parties = mockk<PartyRepository>()
    private val cases = mockk<VerificationCaseRepository>()
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val service = PartyErasureService(parties, cases, Clock.fixed(now, ZoneOffset.UTC))
    private val partyId = UUID.randomUUID()

    private fun liveParty(): Party = mockk {
        every { erasedAt } returns null
        every { externalIds } returns listOf(
            ExternalId(ExternalIdType.KEYCLOAK_ID, "kc", null),
            ExternalId(ExternalIdType.BIRTH_NUMBER, "blind-1", null),
        )
        every { coreAttributes } returns mockk {
            every { familyName } returns "Nováková"
            every { givenName } returns "Jana"
            every { birthdate } returns LocalDate.of(1985, 3, 4)
            every { birthplace } returns "Brno"
        }
    }

    @Test
    fun `erases the party row and the linked cases with one shared timestamp`(): Unit = runBlocking {
        coEvery { parties.findById(partyId) } returns null
        coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 2
        coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.FIRST

        assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.ERASED)

        coVerify(exactly = 1) { parties.anonymize(partyId, now) }
        coVerify(exactly = 1) { cases.anonymizeLinkedTo(partyId, now) }
        coVerify(exactly = 0) { cases.anonymizeApplicantCases(any(), any()) }
    }

    @Test
    fun `unlinked cases are found by the keys read before the party step deletes them`(): Unit = runBlocking {
        val expected = ApplicantCaseKeys(partyId, "blind-1", "novakova|jana|1985-03-04|brno", LocalDate.of(1985, 3, 4))
        coEvery { parties.findById(partyId) } returns liveParty()
        coEvery { cases.anonymizeApplicantCases(expected, now) } returnsMany listOf(2, 0)
        coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.FIRST
        coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 0

        assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.ERASED)

        coVerifyOrder {
            parties.findById(partyId)
            cases.anonymizeApplicantCases(expected, now)
            parties.anonymize(partyId, now)
            cases.anonymizeLinkedTo(partyId, now)
            cases.anonymizeApplicantCases(expected, now)
        }
    }

    @Test
    fun `a re-delivery that finds everything erased reports REPEATED`(): Unit = runBlocking {
        coEvery { parties.findById(partyId) } returns mockk { every { erasedAt } returns OffsetDateTime.now() }
        coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 0
        coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.REPEAT

        assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.REPEATED)
        coVerify(exactly = 0) { cases.anonymizeApplicantCases(any(), any()) }
    }

    @Test
    fun `an unknown party is tombstoned`(): Unit = runBlocking {
        coEvery { parties.findById(partyId) } returns null
        coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 0
        coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.TOMBSTONED

        assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.TOMBSTONED)
    }

    @Test
    fun `the case step still runs when the party row was already erased, so a half-done delivery is completed`(): Unit =
        runBlocking {
            // First delivery erased the party row and died before the cases step; the retry finds
            // the row already erased (false) but must still anonymise the cases.
            coEvery { parties.findById(partyId) } returns mockk { every { erasedAt } returns OffsetDateTime.now() }
            coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 1
            coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.REPEAT

            assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.ERASED)
            coVerify(exactly = 1) { cases.anonymizeLinkedTo(partyId, now) }
        }
}
