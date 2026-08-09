// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseStart
import com.openbank.casecoordinator.infrastructure.config.CaseCoordinatorConfig
import com.openbank.libs.temporal.TemporalConfig
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.api.filter.v1.WorkflowTypeFilter
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowServiceException
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock

/** Result of a case-open attempt — mapped to HTTP statuses by the REST layer, no exceptions. */
sealed interface CaseOpenResult {
    data class Opened(val caseId: String) : CaseOpenResult

    /** The caller holds no `case.open` capability, or the class is not enabled (ADR-0244 D3/D9). */
    data object Denied : CaseOpenResult

    /** A case for this (class, subjectRef) already runs — the dedup window (D9). */
    data object Duplicate : CaseOpenResult

    /** Per-agent hourly open cap or the fleet concurrent-case ceiling is exhausted (D9). */
    data object RateLimited : CaseOpenResult

    /** Temporal is disabled or unreachable; nothing was started. */
    data object Unavailable : CaseOpenResult
}

/**
 * Case-open authority (ADR-0244 D9): the single entry point that starts case workflows.
 * Enforcement order is deliberate — capability first (cheap, in-process), then the in-memory
 * per-agent rate limit, then the Temporal visibility ceiling (network), then the start itself,
 * whose `REJECT_DUPLICATE` reuse policy is the real dedup: workflowId `case-<class>-<subject>`
 * means a second open for the same subject fails atomically on the server, not on a local guess.
 *
 * The visibility count is fail-CLOSED: a ceiling that cannot be measured denies the open,
 * because an uncounted swarm is the failure mode the ceiling exists for.
 */
@ApplicationScoped
class CaseOpenService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val gate: CaseCapabilityGate,
    private val config: CaseCoordinatorConfig,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(CaseOpenService::class.java)
    private val openTimes = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun open(openedBy: String, caseClass: CaseClass, subjectRef: String, dispositionTarget: String): CaseOpenResult {
        if (!temporalConfig.enabled()) return CaseOpenResult.Unavailable
        if (!gate.canOpenCase(openedBy) || caseClass !in config.case().enabledClasses()) {
            return CaseOpenResult.Denied
        }
        val now = clock.millis()
        if (!consumeOpenQuota(openedBy, now)) return CaseOpenResult.RateLimited
        if (countRunningCases() >= config.case().maxConcurrent()) {
            refundOpenQuota(openedBy, now)
            return CaseOpenResult.RateLimited
        }

        val workflowId = workflowIdFor(caseClass, subjectRef)
        val start = CaseStart(
            caseId = workflowId,
            caseClass = caseClass,
            subjectRef = subjectRef,
            openedBy = openedBy,
            dispositionTarget = dispositionTarget,
            deadlineEpochMs = now + config.case().ttl().toMillis(),
            contestedRateThreshold = config.case().contestedRateThreshold(),
            maxContributions = config.case().maxContributions(),
        )
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build()
        // Untyped stub start — same server-side dedup as the typed static WorkflowClient.start,
        // but an instance method, so tests can stub it without mockkStatic gymnastics.
        val stub = workflowClient.newUntypedWorkflowStub(WORKFLOW_TYPE, options)
        return try {
            stub.start(start)
            log.infof("case opened: %s by %s (class %s)", workflowId, openedBy, caseClass)
            CaseOpenResult.Opened(workflowId)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            refundOpenQuota(openedBy, now)
            log.debugf(e, "case open dedup: %s already running on the server", workflowId)
            CaseOpenResult.Duplicate
        } catch (e: WorkflowServiceException) {
            refundOpenQuota(openedBy, now)
            log.warnf(e, "Temporal start failed for %s", workflowId)
            CaseOpenResult.Unavailable
        }
    }

    fun workflowIdFor(caseClass: CaseClass, subjectRef: String): String =
        "case-${caseClass.name.lowercase().replace('_', '-')}-${subjectRef.replace(Regex("[^A-Za-z0-9_-]"), "-")}"

    private fun consumeOpenQuota(agentId: String, now: Long): Boolean {
        val deque = openTimes.getOrPut(agentId) { ArrayDeque() }
        val windowStart = now - OPEN_WINDOW_MS
        while (deque.isNotEmpty() && deque.first() < windowStart) deque.removeFirst()
        if (deque.size >= config.case().maxOpensPerAgentPerHour()) return false
        deque.addLast(now)
        return true
    }

    private fun refundOpenQuota(agentId: String, now: Long) {
        openTimes[agentId]?.remove(now)
    }

    private fun countRunningCases(): Int = try {
        val request = ListOpenWorkflowExecutionsRequest.newBuilder()
            .setNamespace(workflowClient.options.namespace)
            .setTypeFilter(WorkflowTypeFilter.newBuilder().setName(WORKFLOW_TYPE).build())
            .build()
        workflowClient.workflowServiceStubs.blockingStub()
            .listOpenWorkflowExecutions(request)
            .executionsCount
    } catch (e: WorkflowServiceException) {
        log.warnf(e, "running-case count failed; denying open (fail-closed ceiling)")
        Int.MAX_VALUE
    }

    private companion object {
        const val WORKFLOW_TYPE = "CaseWorkflow"
        const val OPEN_WINDOW_MS = 3_600_000L
    }
}
