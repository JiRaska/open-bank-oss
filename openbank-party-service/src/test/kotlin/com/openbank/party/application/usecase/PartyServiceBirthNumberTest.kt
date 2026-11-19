// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.CreatePartyCommand
import com.openbank.party.application.port.`in`.ResolvePartyByRcCommand
import com.openbank.party.application.port.`in`.SearchPartiesQuery
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional

/**
 * The birth-number parser is clock-free and accepts a 10-digit number for a date that has not happened
 * yet (year digits 27..53 read as 2027..2053). Party-service must reject it against its own clock at
 * every call site that accepts a person from a birth number: create, resolve-by-RC and search.
 */
class PartyServiceBirthNumberTest {

    private val today = LocalDate.of(2026, 11, 19)
    private val tomorrow = today.plusDays(1)
    private val distantPast = LocalDate.of(1960, 3, 4)

    private fun newService() = PartyService().apply {
        partyRepo = mockk()
        documentRepo = mockk()
        documentFileRepo = mockk()
        metrics = mockk(relaxed = true)
        changeMetrics = mockk(relaxed = true)
        gdprAggregation = mockk(relaxed = true)
        rcPepper = Optional.of("test-pepper-not-a-secret")
        clock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC)
    }

    // Builds a synthetic, checksum-valid 10-digit birth number for [d] from parts (no literal in source).
    private fun rcFor(d: LocalDate): String {
        val head = "%02d%02d%02d".format(d.year % 100, d.monthValue, d.dayOfMonth)
        for (suffix in 100..999) {
            val nine = head + suffix
            val r = (nine.toLong() % 11).toInt()
            if (r != 10) return nine + r
        }
        error("no valid suffix")
    }

    private suspend fun createdIndex(rc: String, service: PartyService = newService()): String? {
        val savedSlot = slot<Party>()
        val eventSlot = slot<PartyEvent>()
        coEvery { service.partyRepo.findByEmail(any()) } returns null
        coEvery { service.partyRepo.save(capture(savedSlot), capture(eventSlot)) } answers { savedSlot.captured }
        service.createParty(
            CreatePartyCommand(
                idempotencyKey = "k",
                partyType = PartyType.INDIVIDUAL,
                legalName = "Test",
                tradingName = null,
                dateOfBirth = null,
                nationality = null,
                taxId = rc,
                registrationNumber = null,
                email = "rc@example.com",
                phone = null,
                address = null,
            ),
        )
        return savedSlot.captured.rcBlindIndex
    }

    private fun searchService(): PartyService = newService().also {
        coEvery { it.partyRepo.findByRcBlindIndex(any()) } returns null
        coEvery { it.partyRepo.findByIdentifier(any(), any()) } returns emptyList()
        coEvery { it.partyRepo.searchByBusinessKeys(any(), any(), any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `createParty computes no blind index for a birth number dated tomorrow`(): Unit = runBlocking {
        assertThat(createdIndex(rcFor(tomorrow))).isNull()
    }

    @Test
    fun `createParty indexes a birth number dated today`(): Unit = runBlocking {
        assertThat(createdIndex(rcFor(today))).isNotNull()
    }

    @Test
    fun `createParty indexes a child born in Prague today at 00-30 CET while the UTC date is still yesterday`(): Unit =
        runBlocking {
            val service = newService().apply {
                clock =
                    Clock.fixed(Instant.parse("2026-11-19T23:30:00Z"), ZoneOffset.UTC)
            }

            assertThat(createdIndex(rcFor(LocalDate.of(2026, 11, 20)), service)).isNotNull()
            assertThat(createdIndex(rcFor(LocalDate.of(2026, 11, 21)), service)).isNull()
        }

    @Test
    fun `createParty indexes a birth number from the distant past`(): Unit = runBlocking {
        assertThat(createdIndex(rcFor(distantPast))).isNotNull()
    }

    @Test
    fun `resolvePartyByRc never looks up a birth number dated tomorrow`(): Unit = runBlocking {
        val service = newService()
        coEvery { service.partyRepo.findByRcBlindIndex(any()) } returns null

        assertThat(service.resolvePartyByRc(ResolvePartyByRcCommand(rcFor(tomorrow)))).isNull()
        coVerify(exactly = 0) { service.partyRepo.findByRcBlindIndex(any()) }
    }

    @Test
    fun `resolvePartyByRc looks up birth numbers dated today and in the distant past`(): Unit = runBlocking {
        val service = newService()
        coEvery { service.partyRepo.findByRcBlindIndex(any()) } returns null

        service.resolvePartyByRc(ResolvePartyByRcCommand(rcFor(today)))
        service.resolvePartyByRc(ResolvePartyByRcCommand(rcFor(distantPast)))

        coVerify(exactly = 2) { service.partyRepo.findByRcBlindIndex(any()) }
    }

    @Test
    fun `searchParties does not take the blind-index path for a birth number dated tomorrow`(): Unit = runBlocking {
        val service = searchService()

        service.searchParties(SearchPartiesQuery(q = rcFor(tomorrow)))

        coVerify(exactly = 0) { service.partyRepo.findByRcBlindIndex(any()) }
    }

    @Test
    fun `searchParties takes the blind-index path for birth numbers dated today and in the distant past`(): Unit =
        runBlocking {
            val service = searchService()

            service.searchParties(SearchPartiesQuery(q = rcFor(today)))
            service.searchParties(SearchPartiesQuery(q = rcFor(distantPast)))

            coVerify(exactly = 2) { service.partyRepo.findByRcBlindIndex(any()) }
        }
}
