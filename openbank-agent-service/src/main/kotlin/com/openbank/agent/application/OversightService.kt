// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.`in`.ProposalQueries
import com.openbank.agent.domain.model.ChatMessage
import com.openbank.agent.domain.model.ChatRole
import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * The first autonomous control-plane agent (ADR-0031 D9 phase 2): a scheduled oversight sweep
 * under the `compliance-officer` charter. Each run reads the live bank through the same
 * policy-gated MCP tools as the assistant — sanctions screenings pending review, open/escalated
 * AML cases, open disputes — and records anything needing operator attention as a proposal in
 * the HITL approval queue (ADR-0031 D4). It never acts: proposals are its only output, exactly
 * the near-zero blast radius D9 phase 2 prescribes.
 *
 * Governance is inherited, not re-implemented: the run goes through [AgentChatService.run], so
 * the charter tool filter, the OPA policy gate, the charter rate limits (48 runs/day) and the
 * D5 run-level audit all apply unchanged — only the identity, system prompt and trigger differ
 * from the chat path.
 *
 * Off by default (`agent.oversight.enabled`); the sandbox enables it via env. Scheduled runs
 * skip overlapping executions; an operator can also trigger a sweep manually over REST.
 */
@ApplicationScoped
class OversightService {

    @Inject lateinit var chatService: AgentChatService

    @Inject lateinit var proposals: ProposalQueries

    @Inject lateinit var injectionGuard: PromptInjectionGuard

    @Inject lateinit var domainMetrics: DomainMetrics

    @ConfigProperty(name = "agent.oversight.enabled", defaultValue = "false")
    var enabled: Boolean = false

    private val log = Logger.getLogger(OversightService::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(cron = "{agent.oversight.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun scheduledSweep() {
        if (!enabled) return
        // withContext(Dispatchers.IO), and the reason is the opposite of the usual one in this
        // fleet. A `suspend` @Scheduled method is dispatched on a duplicated VERT.X context — which
        // is exactly right for reactive Panache, and exactly wrong here: this service has no
        // reactive datasource (jdbc-postgresql + narayana), and the sweep's collaborators are
        // BLOCKING REST clients. On the event loop each of them throws
        // `BlockingNotAllowedException`, so measured on the sandbox 2026-08-21 every scheduled
        // sweep did this:
        //   BlockedThreadChecker: event-loop-thread-0 blocked for 19s
        //   Tool call failed: sanctions_list_pending: BlockingNotAllowedException
        //   OPA decision query failed ... failing closed: BlockingNotAllowedException
        //   agent policy BLOCK but PDP errored — falling back to ADVISORY
        // The last line is the damage: the PDP is unreachable for reasons that have nothing to do
        // with OPA, so ENFORCEMENT SILENTLY DOWNGRADES on every sweep while the run still reports
        // "oversight sweep done" and the liveness heartbeat still records success.
        //
        // Only the SCHEDULED path needs this. A sweep triggered over HTTP or Kafka already arrives
        // on a worker thread; wrapping there would add a hop for nothing.
        withContext(Dispatchers.IO) { sweep(trigger = "scheduled") }
        liveness?.recordSuccess()
    }

    suspend fun sweep(trigger: String): AgentChatService.ChatOutcome {
        // Pending titles go into the prompt so the model does not re-draft what a human has not
        // yet decided — dedup by instruction, enforced softly (a duplicate is still HITL-gated).
        // Titles are sanitized before splicing into the prompt — a previously stored
        // adversarial title must not be able to instruct this run (D6 guardrail).
        val pendingTitles = proposals.listPending().map { injectionGuard.sanitizeInline(it.title) }
        log.infof("oversight sweep start: trigger=%s pendingProposals=%d", trigger, pendingTitles.size)
        val outcome = chatService.run(
            identity = OVERSIGHT_IDENTITY,
            systemPrompt = systemPrompt(pendingTitles),
            history = listOf(ChatMessage(ChatRole.USER, SWEEP_REQUEST)),
            modelId = null,
            trigger = trigger,
        )
        log.infof(
            "oversight sweep done: trigger=%s model=%s toolCalls=%d proposal=%s",
            trigger,
            outcome.model,
            outcome.toolCalls.size,
            outcome.isProposal,
        )
        return outcome
    }

    private fun systemPrompt(pendingTitles: List<String>): String =
        RegisteredPromptTemplates.oversightPrompt(pendingTitles)

    companion object {
        private const val WORKFLOW_NAME = "agent-oversight-sweep"
        private val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
        val OVERSIGHT_IDENTITY = AgentIdentity(agentId = "compliance-officer", plane = "control")
        const val SWEEP_REQUEST =
            "Run the compliance oversight sweep now. Check sanctions screenings pending review, " +
                "open/escalated AML cases and open disputes, then summarise what you found."
    }
}
