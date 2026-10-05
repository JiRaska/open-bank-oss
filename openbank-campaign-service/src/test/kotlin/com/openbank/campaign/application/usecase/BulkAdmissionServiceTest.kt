// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
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
    fun `missing measured budget prevents a mass run`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 0)
        assertThatThrownBy { runBlocking { service.start(campaignId, "maker") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(store.run).isNull()
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
        val service = BulkAdmissionService(campaigns, enrolment, store, 2)
        val first = UUID.randomUUID()
        val started = service.start(campaignId, "maker")
        coEvery { enrolment.enrolPage(campaignId, null, 2) } returns EnrolmentPageOutcome(1, 1, first, false)

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.cursor).isEqualTo(first)
        assertThat(store.run?.admitted).isEqualTo(1)
        assertThat(store.run?.failures).isEqualTo(1)
        assertThatThrownBy { runBlocking { service.resume(started.id, "maker") } }
            .isInstanceOf(IllegalStateException::class.java)
        service.resume(started.id, "checker")
        coEvery { enrolment.enrolPage(campaignId, first, 2) } returns
            EnrolmentPageOutcome(1, 0, UUID.randomUUID(), true)

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.COMPLETED)
        assertThat(store.run?.admitted).isEqualTo(2)
        coVerify(exactly = 1) { enrolment.enrolPage(campaignId, first, 2) }
    }

    @Test
    fun `an unavailable segment holds without moving the cursor`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 1)
        service.start(campaignId, "maker")
        coEvery { enrolment.enrolPage(campaignId, null, 1) } throws IllegalStateException("source unavailable")

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.cursor).isNull()
        assertThat(store.run?.lastError).isEqualTo("ADMISSION_UNAVAILABLE")
    }

    @Test
    fun `paused campaign holds the run without counting a party failure`(): Unit = runBlocking {
        val service = BulkAdmissionService(campaigns, enrolment, store, 1)
        service.start(campaignId, "maker")
        coEvery { enrolment.enrolPage(campaignId, null, 1) } returns
            EnrolmentPageOutcome(0, 0, null, false, "CAMPAIGN_NOT_ACTIVE")

        service.tick()

        assertThat(store.run?.state).isEqualTo(BulkRunState.HELD)
        assertThat(store.run?.lastError).isEqualTo("CAMPAIGN_NOT_ACTIVE")
        assertThat(store.run?.failures).isEqualTo(0)
    }

    private class RecordingRuns : BulkRunStore {
        var run: BulkRun? = null
        var manualOwner: UUID? = null

        override suspend fun claimManual(owner: UUID): Boolean {
            if (manualOwner != null) return false
            manualOwner = owner
            return true
        }

        override suspend fun releaseManual(owner: UUID) {
            if (manualOwner == owner) manualOwner = null
        }

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
            )
            run = created
            return created
        }

        override suspend fun find(id: UUID): BulkRun? = run?.takeIf { it.id == id }
        override suspend fun list(campaignId: UUID): List<BulkRun> =
            run?.takeIf { it.campaignId == campaignId }?.let(::listOf) ?: emptyList()
        override suspend fun claim(owner: UUID): ClaimedBulkRun? = run?.takeIf { it.state == BulkRunState.RUNNING }
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
        }?.copy(state = BulkRunState.RUNNING, lastError = null)?.also { run = it }
    }
}
