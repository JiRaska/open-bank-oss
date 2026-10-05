// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.SegmentPage
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class BulkRunState { RUNNING, HELD, COMPLETED }

data class BulkRun(
    val id: UUID,
    val campaignId: UUID,
    val state: BulkRunState,
    val cursor: UUID?,
    val pageSize: Int,
    val admitted: Long,
    val failures: Long,
    val lastError: String?,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastResumedBy: String? = null,
    val lastResumedAt: Instant? = null,
)

data class ClaimedBulkRun(val run: BulkRun, val leaseOwner: UUID)

interface BulkRunStore {
    suspend fun create(id: UUID, campaignId: UUID, pageSize: Int, actor: String): BulkRun
    suspend fun find(id: UUID): BulkRun?
    suspend fun list(campaignId: UUID): List<BulkRun>
    suspend fun claim(owner: UUID): ClaimedBulkRun?
    suspend fun finish(claim: ClaimedBulkRun, outcome: EnrolmentPageOutcome)
    suspend fun hold(claim: ClaimedBulkRun, reason: String, outcome: EnrolmentPageOutcome? = null)
    suspend fun resume(id: UUID, actor: String): BulkRun?
    suspend fun claimManual(owner: UUID): Boolean
    suspend fun releaseManual(owner: UUID)
}

/**
 * Admission, not delivery. One global database lease grants one bounded page per minute across
 * campaigns and replicas. The configured page size defaults to zero: a deployment must explicitly
 * set a measured safe budget before an operator can start a mass run.
 */
@ApplicationScoped
class BulkAdmissionService(
    private val campaigns: CampaignRepository,
    private val campaignService: CampaignService,
    private val runs: BulkRunStore,
    @ConfigProperty(name = "openbank.campaign.bulk-admission-per-minute", defaultValue = "0")
    private val pageSize: Int,
) {
    private val log = Logger.getLogger(BulkAdmissionService::class.java)

    private companion object {
        // The database lease lasts one hour. End work before it can be reclaimed by another pod.
        const val MAX_PAGE_MINUTES = 55L
        val PAGE_TIMEOUT_MILLIS: Long = Duration.ofMinutes(MAX_PAGE_MINUTES).toMillis()
    }

    suspend fun start(campaignId: UUID, actor: String): BulkRun {
        require(actor.isNotBlank()) { "actor is required" }
        check(pageSize in 1..SegmentPage.MAX_PAGE_SIZE) { "bulk admission has no measured capacity configuration" }
        val campaign = campaigns.findById(campaignId) ?: throw CampaignNotFoundException(campaignId)
        check(campaign.state == CampaignState.ACTIVE) { "only an ACTIVE campaign can start a bulk run" }
        return runs.create(Ids.newId(), campaignId, pageSize, actor)
    }

    suspend fun find(id: UUID): BulkRun? = runs.find(id)

    suspend fun list(campaignId: UUID): List<BulkRun> = runs.list(campaignId)

    /** Legacy synchronous API is limited by the same global budget as durable runs. */
    suspend fun enrolSmall(campaignId: UUID): EnrolmentOutcome {
        check(pageSize in 1..SegmentPage.MAX_PAGE_SIZE) { "bulk admission has no measured capacity configuration" }
        val owner = Ids.randomId()
        check(runs.claimManual(owner)) { "admission capacity is busy" }
        try {
            return withTimeout(PAGE_TIMEOUT_MILLIS) { campaignService.enrolWithinLimit(campaignId, pageSize) }
        } finally {
            withContext(NonCancellable) { runs.releaseManual(owner) }
        }
    }

    suspend fun resume(id: UUID, actor: String): BulkRun {
        val run = runs.find(id) ?: throw NoSuchElementException("bulk run $id not found")
        val campaign = campaigns.findById(run.campaignId) ?: throw CampaignNotFoundException(run.campaignId)
        check(campaign.state == CampaignState.ACTIVE) { "campaign must be ACTIVE to resume a run" }
        check(actor != run.createdBy) { "a different operator must resume the run" }
        return runs.resume(id, actor) ?: error("only a HELD run can resume")
    }

    /**
     * A failed page retains its previous cursor and is held for review. Repeating the page
     * re-reads successful predecessors, but the campaign/party unique enrolment makes them no-ops.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun tick() {
        val claim = runs.claim(Ids.randomId()) ?: return
        try {
            if (pageSize !in 1..SegmentPage.MAX_PAGE_SIZE || claim.run.pageSize > pageSize) {
                runs.hold(claim, "CAPACITY_BUDGET_REDUCED")
                return
            }
            if (campaigns.findById(claim.run.campaignId)?.state != CampaignState.ACTIVE) {
                runs.hold(claim, "CAMPAIGN_NOT_ACTIVE")
                return
            }
            val result = withTimeout(PAGE_TIMEOUT_MILLIS) {
                campaignService.enrolPage(claim.run.campaignId, claim.run.cursor, claim.run.pageSize)
            }
            if (result.holdReason != null) {
                runs.hold(claim, result.holdReason, result)
            } else if (result.failed > 0) {
                runs.hold(claim, "PARTY_ADMISSION_FAILED", result)
            } else {
                runs.finish(claim, result)
            }
        } catch (e: TimeoutCancellationException) {
            log.errorf(e, "bulk admission timed out run=%s", claim.run.id)
            runs.hold(claim, "ADMISSION_TIMEOUT")
        } catch (e: Exception) {
            log.errorf(e, "bulk admission held run=%s", claim.run.id)
            runs.hold(claim, "ADMISSION_UNAVAILABLE")
        }
    }
}
