// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.AudienceSnapshotPort
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.SegmentPage
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class BulkRunState { PREPARING, RUNNING, HELD, COMPLETED }

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
    val snapshotAt: Instant? = null,
    val audienceCount: Long? = null,
    val lastResumedBy: String? = null,
    val lastResumedAt: Instant? = null,
)

data class ClaimedBulkRun(val run: BulkRun, val leaseOwner: UUID)

/** One authority owns both run state and its frozen recipient ledger under the same lease. */
@Suppress("TooManyFunctions")
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
    suspend fun clearAudience(claim: ClaimedBulkRun)
    suspend fun appendAudience(claim: ClaimedBulkRun, partyIds: List<UUID>)
    suspend fun completeAudience(claim: ClaimedBulkRun)
    suspend fun audiencePage(runId: UUID, after: UUID?, limit: Int): SegmentPage
    suspend fun markRecipient(claim: ClaimedBulkRun, partyId: UUID, state: RecipientAdmissionState)
}

enum class RecipientAdmissionState { STARTING, ADMITTED, SKIPPED, FAILED }

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
    @Inject lateinit var segments: SegmentRegistry

    @Inject lateinit var snapshots: AudienceSnapshotPort
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
            if (claim.run.state == BulkRunState.PREPARING) {
                val campaign =
                    campaigns.findById(claim.run.campaignId) ?: throw CampaignNotFoundException(claim.run.campaignId)
                val segment = segments.load(campaign.segmentRef.name, campaign.segmentRef.version)
                    ?: error("approved campaign segment is unavailable")
                runs.clearAudience(claim)
                withTimeout(PAGE_TIMEOUT_MILLIS) {
                    snapshots.stream(segment) { batch -> runs.appendAudience(claim, batch) }
                }
                runs.completeAudience(claim)
                return
            }
            val page = runs.audiencePage(claim.run.id, claim.run.cursor, claim.run.pageSize)
            val result = withTimeout(PAGE_TIMEOUT_MILLIS) {
                campaignService.enrolParties(
                    claim.run.campaignId,
                    claim.run.cursor,
                    page,
                    claim.run.pageSize,
                ) { partyId, state -> runs.markRecipient(claim, partyId, state) }
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
