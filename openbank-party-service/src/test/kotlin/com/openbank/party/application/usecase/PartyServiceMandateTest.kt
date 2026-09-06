// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.GrantMandateCommand
import com.openbank.party.application.port.`in`.PartyMandateRejectedException
import com.openbank.party.application.port.`in`.RevokeMandateCommand
import com.openbank.party.application.port.out.PartyMandateRepository
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.domain.model.Address
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.MandateAuthority
import com.openbank.party.domain.model.MandateRole
import com.openbank.party.domain.model.MandateSource
import com.openbank.party.domain.model.MandateStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyMandate
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import io.mockk.coEvery
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

/** Representation mandates (ADR-0284 D3). Split from [PartyServiceTest] to stay under detekt's LargeClass threshold. */
class PartyServiceMandateTest {

    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private val company = party(PartyType.COMPANY, "Příklad s.r.o.")
    private val human = party(PartyType.INDIVIDUAL, "Jana Nováková")

    private fun party(type: PartyType, name: String, status: PartyStatus = PartyStatus.ACTIVE) = Party(
        id = UUID.randomUUID(), partyType = type, status = status, legalName = name, tradingName = null,
        dateOfBirth = null, nationality = "CZE", taxId = null,
        registrationNumber = if (type ==
            PartyType.COMPANY
        ) {
            "45274649"
        } else {
            null
        },
        email = "$name@example.test".replace(
            " ",
            ".",
        ),
        phone = null, address = Address("Hlavní 1", null, "Praha", "11000", "CZ"),
        kycStatus = KycStatus.APPROVED, createdAt = now, updatedAt = now,
    )

    private fun service(partyRepo: PartyRepository, mandateRepo: PartyMandateRepository) = PartyService().apply {
        this.partyRepo = partyRepo
        this.mandateRepo = mandateRepo
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun partyRepoWith(vararg parties: Party): PartyRepository = mockk {
        coEvery { findById(any()) } answers { parties.firstOrNull { it.id == firstArg() } }
    }

    @Test
    fun `grant binds a human to an entity, emits the event keyed on the entity, and re-grants upsert`(): Unit =
        runBlocking {
            val mandates = mockk<PartyMandateRepository>()
            coEvery { mandates.findActive(company.id, human.id, "LEGAL_REPRESENTATIVE") } returns null
            val saved = slot<PartyMandate>()
            val event = slot<PartyEvent>()
            coEvery { mandates.save(capture(saved), capture(event)) } answers { firstArg() }

            val result = service(partyRepoWith(company, human), mandates).grantMandate(
                GrantMandateCommand(
                    company.id,
                    human.id,
                    MandateRole.LEGAL_REPRESENTATIVE,
                    MandateAuthority.JOINT,
                    MandateSource.REGISTRY,
                    "kyb-case:1",
                ),
            )

            assertThat(result.status).isEqualTo(MandateStatus.ACTIVE)
            assertThat(saved.captured.validFrom).isEqualTo(now)
            assertThat(event.captured.eventType).isEqualTo("PARTY_MANDATE_GRANTED")
            assertThat(event.captured.aggregateId).isEqualTo(company.id)
            assertThat(event.captured.envelope["agentPartyId"]).isEqualTo(human.id)
            assertThat(event.captured.envelope["sourceService"]).isEqualTo("party-service")

            // Second grant of the same triple updates the existing row instead of stacking a duplicate.
            coEvery { mandates.findActive(company.id, human.id, "LEGAL_REPRESENTATIVE") } returns result
            val updated = slot<PartyMandate>()
            coEvery { mandates.update(capture(updated), any()) } answers { firstArg() }
            service(partyRepoWith(company, human), mandates).grantMandate(
                GrantMandateCommand(
                    company.id,
                    human.id,
                    MandateRole.LEGAL_REPRESENTATIVE,
                    MandateAuthority.SOLE,
                    MandateSource.REGISTRY,
                    "kyb-case:2",
                ),
            )
            assertThat(updated.captured.id).isEqualTo(result.id)
            assertThat(updated.captured.authority).isEqualTo(MandateAuthority.SOLE)
        }

    @Test
    fun `an individual cannot be a principal, an entity cannot be an agent, a closed party cannot take part`(): Unit =
        runBlocking {
            val mandates = mockk<PartyMandateRepository>(relaxed = true)
            val closed = party(PartyType.INDIVIDUAL, "Erased", PartyStatus.CLOSED)
            val svc = service(partyRepoWith(company, human, closed), mandates)

            assertThatThrownBy {
                runBlocking {
                    svc.grantMandate(
                        GrantMandateCommand(
                            human.id,
                            human.id,
                            MandateRole.OWNER,
                            MandateAuthority.SOLE,
                            MandateSource.MANUAL,
                            null,
                        ),
                    )
                }
            }
                .isInstanceOf(PartyMandateRejectedException::class.java).hasMessageContaining("INDIVIDUAL")
            assertThatThrownBy {
                runBlocking {
                    svc.grantMandate(
                        GrantMandateCommand(
                            company.id,
                            company.id,
                            MandateRole.OWNER,
                            MandateAuthority.SOLE,
                            MandateSource.MANUAL,
                            null,
                        ),
                    )
                }
            }
                .isInstanceOf(PartyMandateRejectedException::class.java).hasMessageContaining("natural person")
            assertThatThrownBy {
                runBlocking {
                    svc.grantMandate(
                        GrantMandateCommand(
                            company.id,
                            closed.id,
                            MandateRole.OWNER,
                            MandateAuthority.SOLE,
                            MandateSource.MANUAL,
                            null,
                        ),
                    )
                }
            }
                .isInstanceOf(PartyMandateRejectedException::class.java).hasMessageContaining("CLOSED")
        }

    @Test
    fun `revoke emits PARTY_MANDATE_REVOKED and acting-for lists only ACTIVE mandates over live entities`(): Unit =
        runBlocking {
            val mandates = mockk<PartyMandateRepository>()
            val active = PartyMandate(
                id = UUID.randomUUID(),
                principalPartyId = company.id,
                agentPartyId = human.id,
                role = MandateRole.LEGAL_REPRESENTATIVE,
                authority = MandateAuthority.SOLE,
                source = MandateSource.REGISTRY,
                status = MandateStatus.ACTIVE,
                evidenceRef = null,
                validFrom = now.minusSeconds(10),
                validTo = null,
                createdAt = now,
                updatedAt = now,
            )
            val dissolvedCo = party(PartyType.COMPANY, "Zaniklá s.r.o.", PartyStatus.CLOSED)
            val overClosed = active.copy(id = UUID.randomUUID(), principalPartyId = dissolvedCo.id)
            val expired = active.copy(id = UUID.randomUUID(), validTo = now.minusSeconds(1))
            coEvery { mandates.findByAgent(human.id) } returns listOf(active, overClosed, expired)
            val svc = service(partyRepoWith(company, human, dissolvedCo), mandates)

            val profiles = svc.actingFor(human.id)
            assertThat(profiles).hasSize(1)
            assertThat(profiles.single().party.id).isEqualTo(company.id)

            coEvery { mandates.findById(active.id) } returns active
            val event = slot<PartyEvent>()
            coEvery { mandates.update(any(), capture(event)) } answers { firstArg() }
            val revoked = svc.revokeMandate(RevokeMandateCommand(company.id, active.id, "resigned per register"))
            assertThat(revoked.status).isEqualTo(MandateStatus.REVOKED)
            assertThat(revoked.revokedAt).isEqualTo(now)
            assertThat(event.captured.eventType).isEqualTo("PARTY_MANDATE_REVOKED")
        }
}
