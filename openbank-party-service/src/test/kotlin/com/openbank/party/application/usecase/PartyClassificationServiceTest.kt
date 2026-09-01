// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.CreatePartyCommand
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyClassification
import com.openbank.party.domain.model.PartyType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class PartyClassificationServiceTest {

    @Test
    fun `createParty preserves an explicitly provisioned synthetic classification`(): Unit = runBlocking {
        val service = PartyService().apply {
            partyRepo = mockk()
            documentRepo = mockk()
            documentFileRepo = mockk()
            metrics = mockk(relaxed = true)
            changeMetrics = mockk(relaxed = true)
            gdprAggregation = mockk(relaxed = true)
            rcPepper = Optional.empty()
            clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
        }
        val savedPartySlot = slot<Party>()
        coEvery { service.partyRepo.findByEmail("canary@example.test") } returns null
        coEvery { service.partyRepo.save(capture(savedPartySlot), any()) } answers { savedPartySlot.captured }

        service.createParty(
            CreatePartyCommand(
                idempotencyKey = "synthetic-idem-1",
                partyType = PartyType.INDIVIDUAL,
                legalName = "Synthetic Canary",
                tradingName = null,
                dateOfBirth = null,
                nationality = null,
                taxId = null,
                registrationNumber = null,
                email = "canary@example.test",
                phone = null,
                address = null,
                classification = PartyClassification.SYNTHETIC,
            ),
        )

        assertThat(savedPartySlot.captured.classification).isEqualTo(PartyClassification.SYNTHETIC)
    }
}
