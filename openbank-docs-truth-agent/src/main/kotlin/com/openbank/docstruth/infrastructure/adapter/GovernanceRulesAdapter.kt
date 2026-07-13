// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.application.port.out.GovernanceRulesPort
import com.openbank.docstruth.infrastructure.config.DocsTruthAgentConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.math.abs

/**
 * Best-effort read of `openbank-libs/governance/rules.yaml`'s gate-graduation `enforced:` status
 * (ADR-0144, `read.governance`, ADR-0166 check 3). Not a full YAML parse — `rules.yaml`'s own
 * convention is to reference a gate's script/rule name in a comment right next to its `enforced:`
 * line (see the file's existing `# CI producer is LIVE (advisory): ...` comments), so a
 * proximity-based text scan mirrors that convention directly, the same style as release-steward's
 * `RepoStateReadAdapter` best-effort mirror of a CI script's own logic.
 */
@ApplicationScoped
class GovernanceRulesAdapter(private val config: DocsTruthAgentConfig) : GovernanceRulesPort {

    private val log = Logger.getLogger(GovernanceRulesAdapter::class.java)

    companion object {
        private const val ENFORCED_PREFIX = "enforced:"
        private const val SEARCH_WINDOW = 15
    }

    override suspend fun enforcedStatusFor(gateNamesOrScripts: Set<String>): Map<String, String> {
        if (gateNamesOrScripts.isEmpty()) return emptyMap()
        val file = Path.of(config.repoRoot()).resolve("openbank-libs/governance/rules.yaml")
        if (!file.exists()) return emptyMap()
        val lines = runCatching { file.readLines() }.getOrElse { e ->
            log.warnf(e, "Failed to read %s", file)
            return emptyMap()
        }
        return gateNamesOrScripts
            .associateWith { gate -> nearestEnforcedValue(lines, gate) }
            .filterValues { it != null }
            .mapValues { (_, v) -> v as String }
    }

    private fun nearestEnforcedValue(lines: List<String>, gate: String): String? {
        val mentionIndex = lines.indexOfFirst { it.contains(gate) }
        if (mentionIndex == -1) return null
        val from = (mentionIndex - SEARCH_WINDOW).coerceAtLeast(0)
        val to = (mentionIndex + SEARCH_WINDOW).coerceAtMost(lines.lastIndex)
        return (from..to)
            .map { i -> i to lines[i].trim() }
            .filter { (_, trimmed) -> trimmed.startsWith(ENFORCED_PREFIX) }
            .minByOrNull { (i, _) -> abs(i - mentionIndex) }
            ?.let { (_, trimmed) -> trimmed.removePrefix(ENFORCED_PREFIX).substringBefore("#").trim() }
    }
}
