// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.ErasureOutcome
import com.openbank.pid.application.port.out.PartyAnonymisation
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.application.port.out.VerificationCaseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PartyErasureServiceTest {

    private val parties = mockk<PartyRepository>()
    private val cases = mockk<VerificationCaseRepository>()
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val service = PartyErasureService(parties, cases, Clock.fixed(now, ZoneOffset.UTC))
    private val partyId = UUID.randomUUID()

    @Test
    fun `erases the party row and the linked cases with one shared timestamp`(): Unit = runBlocking {
        coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 2
        coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.FIRST

        assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.ERASED)

        coVerify(exactly = 1) { parties.anonymize(partyId, now) }
        coVerify(exactly = 1) { cases.anonymizeLinkedTo(partyId, now) }
    }

    @Test
    fun `a re-delivery that finds everything erased reports REPEATED, and an unknown party UNKNOWN`(): Unit =
        runBlocking {
            coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 0
            coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.REPEAT

            assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.REPEATED)
        }

    @Test
    fun `an unknown party reports UNKNOWN`(): Unit = runBlocking {
        coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 0
        coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.UNKNOWN

        assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.UNKNOWN)
    }

    @Test
    fun `the case step still runs when the party row was already erased, so a half-done delivery is completed`(): Unit =
        runBlocking {
            // First delivery erased the party row and died before the cases step; the retry finds
            // the row already erased (false) but must still anonymise the cases.
            coEvery { cases.anonymizeLinkedTo(partyId, now) } returns 1
            coEvery { parties.anonymize(partyId, now) } returns PartyAnonymisation.REPEAT

            assertThat(service.erase(partyId)).isEqualTo(ErasureOutcome.ERASED)
            coVerify(exactly = 1) { cases.anonymizeLinkedTo(partyId, now) }
        }
}
