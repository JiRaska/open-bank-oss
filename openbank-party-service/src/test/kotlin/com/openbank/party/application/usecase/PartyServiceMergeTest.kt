// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.MergePartyCommand
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
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
import java.util.Optional
import java.util.UUID

/** ADR-0179 — duplicate party identity merge. */
class PartyServiceMergeTest {

    private val now = Instant.parse("2025-01-01T00:00:00Z")
    private val sourceId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val targetId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val thirdId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private fun service() = PartyService().apply {
        partyRepo = mockk()
        documentRepo = mockk()
        documentFileRepo = mockk()
        gdprAggregation = mockk(relaxed = true)
        accountGuard = mockk()
        metrics = mockk(relaxed = true)
        rcPepper = Optional.empty()
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun party(id: UUID, status: PartyStatus = PartyStatus.ACTIVE, mergedInto: UUID? = null) = Party(
        id = id,
        partyType = PartyType.INDIVIDUAL,
        status = status,
        legalName = "Person $id",
        tradingName = null,
        dateOfBirth = "1990-01-01",
        nationality = "CZ",
        taxId = null,
        registrationNumber = null,
        email = "$id@example.com",
        phone = null,
        address = null,
        kycStatus = KycStatus.APPROVED,
        createdAt = now,
        updatedAt = now,
        amlStatus = AmlStatus.CLEARED,
        mergedIntoPartyId = mergedInto,
    )

    private fun cmd(from: UUID = sourceId, into: UUID = targetId) = MergePartyCommand(
        id = from,
        mergedIntoPartyId = into,
        reason = "duplicate identity",
        approvalReference = "APR-1",
    )

    @Test
    fun `merge retires the source, points it at the survivor and publishes PARTY_MERGED`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(sourceId) } returns party(sourceId)
        coEvery { service.partyRepo.findById(targetId) } returns party(targetId)
        coEvery { service.accountGuard.findOpenAccounts(sourceId) } returns emptyList()
        val saved = slot<Party>()
        val eventSlot = slot<PartyEvent>()
        coEvery { service.partyRepo.update(capture(saved), capture(eventSlot)) } answers { saved.captured }

        val result = service.mergeParty(cmd())

        assertThat(saved.captured.status).isEqualTo(PartyStatus.MERGED)
        assertThat(saved.captured.mergedIntoPartyId).isEqualTo(targetId)
        // PII must survive a merge — that is the whole point of not reusing GDPR erasure.
        assertThat(saved.captured.legalName).isEqualTo("Person $sourceId")
        assertThat(saved.captured.email).isEqualTo("$sourceId@example.com")
        // The event is written through the repository, in the same transaction as the status
        // change (issue #4007) — not emitted afterwards by a separate publisher.
        assertThat(eventSlot.captured.eventType).isEqualTo("PARTY_MERGED")
        assertThat(eventSlot.captured.aggregateId).isEqualTo(result.id)
        assertThat(eventSlot.captured.envelope["mergedIntoPartyId"]).isEqualTo(targetId)
        // A merge is NOT an erasure; emitting one would tell consumers a subject-rights request happened.
        coVerify(exactly = 0) { service.partyRepo.anonymize(any(), any()) }
    }

    @Test
    fun `merge is refused while the source still owns an open account`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(sourceId) } returns party(sourceId)
        coEvery { service.partyRepo.findById(targetId) } returns party(targetId)
        coEvery { service.accountGuard.findOpenAccounts(sourceId) } returns listOf("CZ6508000000192000145399")

        assertThatThrownBy { runBlocking { service.mergeParty(cmd()) } }
            .isInstanceOf(PartyMergeRejectedException::class.java)
            .hasMessageContaining("CZ6508000000192000145399")

        coVerify(exactly = 0) { service.partyRepo.update(any()) }
    }

    @Test
    fun `an unreachable account guard aborts the merge rather than allowing it`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(sourceId) } returns party(sourceId)
        coEvery { service.partyRepo.findById(targetId) } returns party(targetId)
        coEvery { service.accountGuard.findOpenAccounts(sourceId) } throws RuntimeException("connection refused")

        // Fail-closed: "we could not ask" must never be treated as "owns nothing".
        assertThatThrownBy { runBlocking { service.mergeParty(cmd()) } }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("connection refused")

        coVerify(exactly = 0) { service.partyRepo.update(any()) }
        coVerify(exactly = 0) { service.partyRepo.update(any(), any()) }
    }

    @Test
    fun `a party cannot be merged into itself`(): Unit = runBlocking {
        val service = service()

        assertThatThrownBy { runBlocking { service.mergeParty(cmd(from = sourceId, into = sourceId)) } }
            .isInstanceOf(PartyMergeRejectedException::class.java)
            .hasMessageContaining("cannot be merged into itself")
    }

    @Test
    fun `an already-merged source is refused`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(sourceId) } returns
            party(sourceId, PartyStatus.MERGED, mergedInto = thirdId)
        coEvery { service.partyRepo.findById(targetId) } returns party(targetId)

        assertThatThrownBy { runBlocking { service.mergeParty(cmd()) } }
            .isInstanceOf(PartyMergeRejectedException::class.java)
            .hasMessageContaining("already merged")
    }

    @Test
    fun `merging into an already-merged target is refused so no chains form`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(sourceId) } returns party(sourceId)
        coEvery { service.partyRepo.findById(targetId) } returns
            party(targetId, PartyStatus.MERGED, mergedInto = thirdId)

        assertThatThrownBy { runBlocking { service.mergeParty(cmd()) } }
            .isInstanceOf(PartyMergeRejectedException::class.java)
            .hasMessageContaining("merge into the surviving party instead")
    }

    @Test
    fun `an erased source is refused`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(sourceId) } returns party(sourceId, PartyStatus.CLOSED)
        coEvery { service.partyRepo.findById(targetId) } returns party(targetId)

        assertThatThrownBy { runBlocking { service.mergeParty(cmd()) } }
            .isInstanceOf(PartyMergeRejectedException::class.java)
            .hasMessageContaining("erased")
    }

    @Test
    fun `a missing target is a 404, not a merge`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(sourceId) } returns party(sourceId)
        coEvery { service.partyRepo.findById(targetId) } returns null

        assertThatThrownBy { runBlocking { service.mergeParty(cmd()) } }
            .isInstanceOf(PartyNotFoundException::class.java)
    }

    @Test
    fun `a late KYC callback never resurrects a merged party`(): Unit = runBlocking {
        val service = service()
        val merged = party(sourceId, PartyStatus.MERGED, mergedInto = targetId)
        coEvery { service.partyRepo.findById(sourceId) } returns merged
        val saved = slot<Party>()
        val eventSlot = slot<PartyEvent>()
        coEvery { service.partyRepo.update(capture(saved), capture(eventSlot)) } answers { saved.captured }

        service.updateKycStatus(sourceId, KycStatus.APPROVED)

        assertThat(saved.captured.status).isEqualTo(PartyStatus.MERGED)
        assertThat(saved.captured.mergedIntoPartyId).isEqualTo(targetId)
    }
}
