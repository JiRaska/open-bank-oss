// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.config

import com.openbank.casecoordinator.domain.model.CaseClass
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

    interface CaseGroup {
        @WithDefault("case-coordinator")
        fun openAgents(): Set<String>

        @WithDefault("case-coordinator")
        fun swarmAgents(): Set<String>

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
}
