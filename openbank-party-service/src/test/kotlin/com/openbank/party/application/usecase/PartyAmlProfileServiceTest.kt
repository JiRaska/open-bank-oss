// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.DeclareAmlProfileCommand
import com.openbank.party.application.port.out.PartyAmlProfileRepository
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.domain.model.AmlDerivedFacts
import com.openbank.party.domain.model.AmlProfileNotApplicableException
import com.openbank.party.domain.model.AmlRiskFactor
import com.openbank.party.domain.model.CrsStatus
import com.openbank.party.domain.model.FatcaStatus
import com.openbank.party.domain.model.InvalidAmlProfileException
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyAmlProfile
import com.openbank.party.domain.model.PartyAmlProfileTest
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import com.openbank.party.domain.model.PepCategory
import com.openbank.party.domain.model.PepDeclaration
import com.openbank.party.domain.model.TaxResidency
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
import java.time.ZoneOffset
import java.util.UUID

class PartyAmlProfileServiceTest {

    private val now = Instant.parse("2026-09-17T09:00:00Z")
    private val person = party(PartyType.INDIVIDUAL)

    private fun party(type: PartyType, status: PartyStatus = PartyStatus.ACTIVE) = Party(
        id = UUID.randomUUID(), partyType = type, status = status, legalName = "Jana Nováková",
        tradingName = null, dateOfBirth = "1990-01-01", nationality = "CZ", taxId = null,
        registrationNumber = null, email = "jana-${UUID.randomUUID()}@example.test", phone = null,
        address = null, kycStatus = KycStatus.APPROVED, createdAt = now, updatedAt = now,
    )

    private val profileSlot = slot<PartyAmlProfile>()
    private val factsSlot = slot<AmlDerivedFacts>()
    private val eventSlot = slot<PartyEvent>()

    private fun service(
        target: Party,
        current: PartyAmlProfile? = null,
    ): Pair<PartyAmlProfileService, PartyAmlProfileRepository> {
        val repo = mockk<PartyAmlProfileRepository> {
            coEvery { findCurrent(target.id) } returns current
            coEvery { saveNewVersion(capture(profileSlot), capture(factsSlot), capture(eventSlot)) } answers
                { firstArg() }
        }
        val svc = PartyAmlProfileService().apply {
            partyRepo =
                mockk<PartyRepository> { coEvery { findById(any()) } answers { target.takeIf { it.id == firstArg() } } }
            profileRepo = repo
            changeMetrics = mockk(relaxed = true)
            clock = Clock.fixed(now, ZoneOffset.UTC)
            highRiskCountries = listOf("IR", " kp ")
        }
        return svc to repo
    }

    @Test
    fun `the first declaration is version 1 and a re-declaration increments from the current version`(): Unit =
        runBlocking {
            val (first, _) = service(person)
            val v1 = first.declareAmlProfile(DeclareAmlProfileCommand(person.id, PartyAmlProfileTest.valid(), "edge"))
            assertThat(v1.version).isEqualTo(1)
            assertThat(v1.declaredAt).isEqualTo(now)
            assertThat(v1.declaredBy).isEqualTo("edge")

            val (second, _) = service(person, current = v1.copy(version = 4))
            val next = second.declareAmlProfile(
                DeclareAmlProfileCommand(person.id, PartyAmlProfileTest.valid(), "edge"),
            )
            assertThat(next.version).isEqualTo(5)
        }

    @Test
    fun `the derived facts and risk factors travel to the repository and onto PARTY_UPDATED`(): Unit = runBlocking {
        val (svc, _) = service(person)
        val declaration = PartyAmlProfileTest.valid().copy(
            pep = PepDeclaration(true, PepCategory.MINISTER, null),
            taxResidencies = listOf(TaxResidency("CZ", null), TaxResidency("KP", null)),
        )

        val saved = svc.declareAmlProfile(DeclareAmlProfileCommand(person.id, declaration, "edge"))

        assertThat(saved.riskFactors).containsExactly(AmlRiskFactor.PEP, AmlRiskFactor.HIGH_RISK_COUNTRY)
        assertThat(factsSlot.captured)
            .isEqualTo(AmlDerivedFacts(true, PepCategory.MINISTER, FatcaStatus.NON_US, CrsStatus.REPORTABLE))
        val envelope = eventSlot.captured.envelope
        assertThat(eventSlot.captured.eventType).isEqualTo("PARTY_UPDATED")
        assertThat(envelope["partyId"]).isEqualTo(person.id)
        assertThat(envelope["pepFlag"]).isEqualTo(true)
        assertThat(envelope["pepCategory"]).isEqualTo("MINISTER")
        assertThat(envelope["crsStatus"]).isEqualTo("REPORTABLE")
        assertThat(envelope["fatcaStatus"]).isEqualTo("NON_US")
        assertThat(envelope["amlRiskFactors"]).isEqualTo(listOf("PEP", "HIGH_RISK_COUNTRY"))
        assertThat(envelope["eddRequired"]).isEqualTo(true)
        assertThat(envelope["amlProfileVersion"]).isEqualTo(1)
        // The broadcast carries the facts that drive review, never the declaration's free text.
        assertThat(envelope.keys).doesNotContain("taxResidencies", "tin", "incomeSources", "purposeNote")
        assertThat(envelope["materiality"]).isEqualTo("NON_MATERIAL")
    }

    @Test
    fun `an invalid declaration is rejected before anything is stored`(): Unit = runBlocking {
        val (svc, repo) = service(person)
        assertThatThrownBy {
            runBlocking {
                svc.declareAmlProfile(
                    DeclareAmlProfileCommand(person.id, PartyAmlProfileTest.valid().copy(truthful = false), "edge"),
                )
            }
        }.isInstanceOf(InvalidAmlProfileException::class.java)
        coVerify(exactly = 0) { repo.saveNewVersion(any(), any(), any()) }
    }

    @Test
    fun `an unknown party is not found for both read and declare`(): Unit = runBlocking {
        val (svc, _) = service(person)
        val stranger = UUID.randomUUID()
        assertThatThrownBy { runBlocking { svc.getAmlProfile(stranger) } }
            .isInstanceOf(PartyNotFoundException::class.java)
        assertThatThrownBy {
            runBlocking {
                svc.declareAmlProfile(DeclareAmlProfileCommand(stranger, PartyAmlProfileTest.valid(), "edge"))
            }
        }.isInstanceOf(PartyNotFoundException::class.java)
    }

    @Test
    fun `a legal entity or a retired person cannot declare a personal AML profile`(): Unit = runBlocking {
        listOf(
            party(PartyType.COMPANY),
            party(PartyType.INDIVIDUAL, PartyStatus.CLOSED),
            party(PartyType.INDIVIDUAL, PartyStatus.MERGED),
        ).forEach { target ->
            val (svc, repo) = service(target)
            assertThatThrownBy {
                runBlocking {
                    svc.declareAmlProfile(DeclareAmlProfileCommand(target.id, PartyAmlProfileTest.valid(), "edge"))
                }
            }.isInstanceOf(AmlProfileNotApplicableException::class.java)
            coVerify(exactly = 0) { repo.saveNewVersion(any(), any(), any()) }
        }
    }
}
