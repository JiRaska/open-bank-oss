// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.config

import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseDeliveryMode
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/**
 * Configuration for the case-coordinator agent (ADR-0244).
 */
@ConfigMapping(prefix = "openbank.case-coordinator")
@ApplicationScoped
interface CaseCoordinatorConfig {

    @WithDefault("0 0 5 * * ?")
    fun sweepCron(): String

    @WithDefault("https://api.deepinfra.com/v1/openai")
    fun modelEndpoint(): String

    @WithDefault("deepseek-ai/DeepSeek-V3.2")
    fun modelId(): String

    /**
     * Case swarm settings (`openbank.case-coordinator.case.*`), mirroring
     * `agents.yaml: case_classes` for the pilot class incident-response (20 min wall clock,
     * 15 open cases, 40 contributions, 0.35 contested threshold). The capability lists mirror
     * charter `case_capabilities` — today ONLY case-coordinator holds any; swarm join/contribute
     * grants are deliberate follow-up charter work.
     */
    fun case(): CaseGroup

    interface CaseGroup : CaseDeliveryGroup {
        @WithDefault("case-coordinator")
        fun openAgents(): Set<String>

        @WithDefault("case-coordinator")
        fun swarmAgents(): Set<String>

        /**
         * Which agent identity an authenticated caller may ACT AS, keyed by the caller's verified
         * role: `ROLE_A=agent1,agent2;ROLE_B=agent3`. Deny-by-default — a role with no entry may
         * assert no agent identity at all, and there is deliberately no wildcard.
         *
         * This is a different question from [openAgents]/[swarmAgents], and the distinction is the
         * whole point (#4834). Those lists say which agent identities hold a capability; this one
         * says which of them a given caller is allowed to claim to be. Without it the capability
         * lists were consulted against a string taken from the request body, so the decision tested
         * an asserted identity rather than a proved one.
         *
         * The default binds both admitted roles to the single chartered opener, which is exactly
         * today's reachable set — so this changes no behaviour now. What it changes is later: the
         * config comment above says the capability lists are expected to grow, and a new entry
         * there no longer becomes assertable by every operator on its own.
         */
        @WithDefault("ROLE_ADMIN=case-coordinator;ROLE_OPERATOR=case-coordinator")
        fun identityBindings(): String

        @WithDefault("INCIDENT_RESPONSE")
        fun enabledClasses(): Set<CaseClass>

        @WithDefault("15")
        fun maxConcurrent(): Int

        @WithDefault("1")
        fun maxOpensPerAgentPerHour(): Int

        @WithDefault("PT20M")
        fun ttl(): Duration

        @WithDefault("0.35")
        fun contestedRateThreshold(): Double

        @WithDefault("40")
        fun maxContributions(): Int
    }

    interface CaseDeliveryGroup {
        /** Shadow mode is valid only for the bounded non-money-path incident-response pilot. */
        @WithDefault("HITL")
        fun deliveryMode(): CaseDeliveryMode

        fun shadowRolloutId(): java.util.Optional<String>
    }
}
