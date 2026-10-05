// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.AudienceSnapshotPort
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.SegmentPage
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BulkAdmissionServiceTest {
    private val campaignId = UUID.randomUUID()
    private val campaign = mockk<Campaign> { every { state } returns CampaignState.ACTIVE }
    private val campaigns = mockk<CampaignRepository> {
        coEvery { findById(campaignId) } returns campaign
    }
    private val enrolment = mockk<CampaignService>()
    private val store = RecordingRuns()

    @Test
    fun `a run freezes its audience before admitting any journey`(): Unit = runBlocking {
        val segment = mockk<Segment>()
        val approved = mockk<Campaign> {
            every { state } returns CampaignState.ACTIVE
            every { segmentRef } returns SegmentRef("actives", 1)
        }
        val campaignStore = mockk<CampaignRepository> {
            coEvery { findById(campaignId) } returns approved
        }
        val registry = mockk<SegmentRegistry> {
            coEvery { load("actives", 1) } returns segment
        }
        val partyId = UUID.randomUUID()
        val source = object : AudienceSnapshotPort {
            override suspend fun stream(segment: Segment, accept: suspend (List<UUID>) -> Unit) {
                accept(listOf(partyId))
            }
        }
        val service = BulkAdmissionService(campaignStore, enrolment, store, 2, true, deadlineMinutes = 10).also {
            it.segments = registry
            it.snapshots = source
        }
        val started = service.start(campaignId, "maker")
        store.run = started.copy(state = BulkRunState.PREPARING, snapshotAt = null, audienceCount = null)

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.RUNNING)
        assertThat(store.run?.audienceCount).isEqualTo(1)
        assertThat(store.audience).containsExactly(partyId)
        coVerify(exactly = 0) { enrolment.enrolParties(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `audience above configured limit holds preparation before any journey starts`(): Unit = runBlocking {
        val approved = mockk<Campaign> {
            every { state } returns CampaignState.ACTIVE
            every { segmentRef } returns SegmentRef("actives", 1)
        }
        val campaignStore = mockk<CampaignRepository> {
            coEvery { findById(campaignId) } returns approved
        }
        val registry = mockk<SegmentRegistry> {
            coEvery { load("actives", 1) } returns mockk<Segment>()
        }
        val source = object : AudienceSnapshotPort {
            override suspend fun stream(segment: Segment, accept: suspend (List<UUID>) -> Unit) {
                accept(List(2) { UUID.randomUUID() })
                accept(listOf(UUID.randomUUID()))
            }
        }
        val service = BulkAdmissionService(campaignStore, enrolment, store, 2, true, 2, 10).also {
            it.segments = registry
            it.snapshots = source
        }
        store.run = service.start(campaignId, "maker")
            .copy(state = BulkRunState.PREPARING, snapshotAt = null, audienceCount = null)

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.lastError).isEqualTo("AUDIENCE_LIMIT_EXCEEDED")
        assertThat(store.run?.snapshotAt).isNull()
        assertThat(store.audience).hasSize(2)
        coVerify(exactly = 0) { enrolment.enrolParties(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `audience limit cannot exceed the supported maximum`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 2, true, 100_001, 10)

        assertThatThrownBy { runBlocking { service.start(campaignId, "maker") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(store.run).isNull()
    }

    @Test
    fun `lowering audience limit holds a prepared run before the next page`(): Unit = runBlocking {
        val started = BulkAdmissionService(campaigns, enrolment, store, 2, true, deadlineMinutes = 10)
            .start(campaignId, "maker")
        store.run = started.copy(audienceCount = 3)
        val reduced = BulkAdmissionService(campaigns, enrolment, store, 2, true, 2, 10)

        reduced.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.lastError).isEqualTo("AUDIENCE_LIMIT_REDUCED")
        coVerify(exactly = 0) { enrolment.enrolParties(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failed snapshot is discarded and rebuilt before admission`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val approved = mockk<Campaign> {
            every { state } returns CampaignState.ACTIVE
            every { segmentRef } returns SegmentRef("actives", 1)
        }
        val campaignStore = mockk<CampaignRepository> {
            coEvery { findById(campaignId) } returns approved
        }
        val registry = mockk<SegmentRegistry> {
            coEvery { load("actives", 1) } returns mockk<Segment>()
        }
        var attempts = 0
        val source = object : AudienceSnapshotPort {
            override suspend fun stream(segment: Segment, accept: suspend (List<UUID>) -> Unit) {
                attempts++
                accept(listOf(partyId))
                if (attempts == 1) error("source stopped mid-snapshot")
            }
        }
        val service = BulkAdmissionService(campaignStore, enrolment, store, 2, true, deadlineMinutes = 10).also {
            it.segments = registry
            it.snapshots = source
        }
        store.run = service.start(campaignId, "maker")
            .copy(state = BulkRunState.PREPARING, snapshotAt = null, audienceCount = null)

        service.tick()
        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.snapshotAt).isNull()
        coVerify(exactly = 0) { enrolment.enrolParties(any(), any(), any(), any(), any()) }

        service.resume(requireNotNull(store.run).id, "checker")
        service.tick()
        assertThat(store.run?.state).isEqualTo(BulkRunState.RUNNING)
        assertThat(store.audience).containsExactly(partyId)
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `missing measured budget prevents a mass run`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 0, true, deadlineMinutes = 10)
        assertThatThrownBy { runBlocking { service.start(campaignId, "maker") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(store.run).isNull()
    }

    @Test
    fun `missing completion deadline prevents a mass run`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 2, true)

        assertThatThrownBy { runBlocking { service.start(campaignId, "maker") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("deadline")
        assertThat(store.run).isNull()
    }

    @Test
    fun `infeasible admission deadline holds before a recipient starts`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 2, true, deadlineMinutes = 50)
        val started = service.start(campaignId, "maker")
        store.run = started.copy(audienceCount = 101)

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.lastError).isEqualTo("ADMISSION_DEADLINE_INFEASIBLE")
        coVerify(exactly = 0) { enrolment.enrolParties(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `small-run capacity does not unlock mass activation`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 2)

        assertThatThrownBy { runBlocking { service.start(campaignId, "maker") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("rollout gate")
        assertThat(store.run).isNull()
    }

    @Test
    fun `disabling mass activation holds an existing run before another party is admitted`(): Unit = runBlocking {
        BulkAdmissionService(campaigns, enrolment, store, 2, true, deadlineMinutes = 10).start(campaignId, "maker")
        val disabled = BulkAdmissionService(campaigns, enrolment, store, 2)

        disabled.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.lastError).isEqualTo("MASS_ACTIVATION_DISABLED")
        coVerify(exactly = 0) { enrolment.enrolParties(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `legacy enrolment uses and releases the shared capacity lease`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 2)
        coEvery { enrolment.enrolWithinLimit(campaignId, 2) } returns EnrolmentOutcome(1, 0)

        assertThat(service.enrolSmall(campaignId).enrolled).isEqualTo(1)

        assertThat(store.manualOwner).isNull()
        coVerify(exactly = 1) { enrolment.enrolWithinLimit(campaignId, 2) }
    }

    @Test
    fun `legacy admission cannot bypass an occupied global lease`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 2)
        store.manualOwner = UUID.randomUUID()

        assertThatThrownBy { runBlocking { service.enrolSmall(campaignId) } }
            .isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 0) { enrolment.enrolWithinLimit(any(), any()) }
    }

    @Test
    fun `a failed party holds the run at its last successful cursor`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 2, true, deadlineMinutes = 10)
        val first = UUID.randomUUID()
        val started = service.start(campaignId, "maker")
        coEvery { enrolment.enrolParties(campaignId, null, any(), 2, any()) } returns
            EnrolmentPageOutcome(1, 1, first, false)

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.cursor).isEqualTo(first)
        assertThat(store.run?.admitted).isEqualTo(1)
        assertThat(store.run?.failures).isEqualTo(1)
        assertThatThrownBy { runBlocking { service.resume(started.id, "maker") } }
            .isInstanceOf(IllegalStateException::class.java)
        service.resume(started.id, "checker")
        assertThat(store.run?.lastResumedBy).isEqualTo("checker")
        assertThat(store.run?.lastResumedAt).isNotNull()
        coEvery { enrolment.enrolParties(campaignId, first, any(), 2, any()) } returns
            EnrolmentPageOutcome(1, 0, UUID.randomUUID(), true)

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.COMPLETED)
        assertThat(store.run?.admitted).isEqualTo(2)
        coVerify(exactly = 1) { enrolment.enrolParties(campaignId, first, any(), 2, any()) }
    }

    @Test
    fun `an unavailable segment holds without moving the cursor`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 1, true, deadlineMinutes = 10)
        service.start(campaignId, "maker")
        coEvery { enrolment.enrolParties(campaignId, null, any(), 1, any()) } throws
            IllegalStateException("source unavailable")

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.cursor).isNull()
        assertThat(store.run?.lastError).isEqualTo("ADMISSION_UNAVAILABLE")
    }

    @Test
    fun `paused campaign holds the run without counting a party failure`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 1, true, deadlineMinutes = 10)
        service.start(campaignId, "maker")
        coEvery { enrolment.enrolParties(campaignId, null, any(), 1, any()) } returns
            EnrolmentPageOutcome(0, 0, null, false, "CAMPAIGN_NOT_ACTIVE")

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.lastError).isEqualTo("CAMPAIGN_NOT_ACTIVE")
        assertThat(store.run?.failures).isEqualTo(0)
    }

    private class RecordingRuns : BulkRunStore {
        var run: BulkRun? = null
        var manualOwner: UUID? = null
        val audience = mutableListOf<UUID>()

        override suspend fun claimManual(owner: UUID): Boolean {
            if (manualOwner != null) return false
            manualOwner = owner
            return true
        }

        override suspend fun releaseManual(owner: UUID) {
            if (manualOwner == owner) manualOwner = null
        }

        override suspend fun clearAudience(claim: ClaimedBulkRun) {
            audience.clear()
        }
        override suspend fun appendAudience(claim: ClaimedBulkRun, partyIds: List<UUID>) {
            audience.addAll(partyIds)
        }
        override suspend fun completeAudience(claim: ClaimedBulkRun) {
            run = requireNotNull(run).copy(
                state = BulkRunState.RUNNING,
                snapshotAt = Instant.now(),
                audienceCount = audience.size.toLong(),
            )
        }
        override suspend fun audiencePage(runId: UUID, after: UUID?, limit: Int): SegmentPage {
            val ids = List(limit) { UUID.randomUUID() }
            return SegmentPage(ids, ids.lastOrNull())
        }
        override suspend fun markRecipient(claim: ClaimedBulkRun, partyId: UUID, state: RecipientAdmissionState) = Unit

        override suspend fun create(id: UUID, campaignId: UUID, pageSize: Int, actor: String): BulkRun {
            val created = BulkRun(
                id = id,
                campaignId = campaignId,
                state = BulkRunState.RUNNING,
                cursor = null,
                pageSize = pageSize,
                admitted = 0,
                failures = 0,
                lastError = null,
                createdBy = actor,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                snapshotAt = Instant.now(),
                audienceCount = 0,
            )
            run = created
            return created
        }

        override suspend fun find(id: UUID): BulkRun? = run?.takeIf { it.id == id }
        override suspend fun list(campaignId: UUID): List<BulkRun> =
            run?.takeIf { it.campaignId == campaignId }?.let(::listOf) ?: emptyList()
        override suspend fun claim(owner: UUID): ClaimedBulkRun? = run?.takeIf {
            it.state == BulkRunState.PREPARING || it.state == BulkRunState.RUNNING
        }
            ?.let { ClaimedBulkRun(it, owner) }

        override suspend fun finish(claim: ClaimedBulkRun, outcome: EnrolmentPageOutcome) {
            run = requireNotNull(run).copy(
                state = if (outcome.complete) BulkRunState.COMPLETED else BulkRunState.RUNNING,
                cursor = outcome.nextCursor,
                admitted = requireNotNull(run).admitted + outcome.enrolled,
            )
        }

        override suspend fun hold(claim: ClaimedBulkRun, reason: String, outcome: EnrolmentPageOutcome?) {
            run = requireNotNull(run).copy(
                state = BulkRunState.HELD,
                cursor = outcome?.nextCursor ?: run?.cursor,
                admitted = requireNotNull(run).admitted + (outcome?.enrolled ?: 0),
                failures = requireNotNull(run).failures + (outcome?.failed ?: 0),
                lastError = reason,
            )
        }

        override suspend fun resume(id: UUID, actor: String): BulkRun? = run?.takeIf {
            it.id == id && it.state == BulkRunState.HELD && it.createdBy != actor
        }?.let { held ->
            held.copy(
                state = if (held.snapshotAt == null) BulkRunState.PREPARING else BulkRunState.RUNNING,
                lastError = null,
                lastResumedBy = actor,
                lastResumedAt = Instant.now(),
            )
        }
            ?.also { run = it }
    }
}
