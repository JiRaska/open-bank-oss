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
        private const val GATE_KEY_PREFIX = "gate:"
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

    // Anchored on the actual `gate: <name>` YAML key, not a bare substring match anywhere in the
    // file — a bare `it.contains(gate)` also matches the gate name inside an unrelated comment or
    // prose mention (e.g. a `ci_producer` path referencing the gate's check script), which can sit
    // far from the real rule block and land the nearest-`enforced:` search on a NEIGHBORING gate's
    // value instead. Anchoring on the exact key line fixes the common case; the residual risk is
    // rules.yaml genuinely reusing the same gate name for two distinct rules (it does today, for
    // "version-bump" — service_code_change and admin_ui_code_change). For that case, collect every
    // matching gate-key line and only report a value when they all agree; a disagreement is
    // resolved by returning null (no finding) rather than silently attributing one rule's
    // enforcement state to the other.
    private fun nearestEnforcedValue(lines: List<String>, gate: String): String? {
        val anchors = lines.indices.filter { i -> isGateKeyLine(lines[i], gate) }
        if (anchors.isEmpty()) return null
        val values = anchors.mapNotNull { anchor -> enforcedNear(lines, anchor) }.distinct()
        return when (values.size) {
            1 -> values.single()
            0 -> null
            else -> {
                log.warnf(
                    "Gate '%s' is defined %d times in rules.yaml with conflicting enforced " +
                        "values %s -- refusing to attribute a single value rather than risk " +
                        "reporting the wrong gate's enforcement status",
                    gate,
                    anchors.size,
                    values,
                )
                null
            }
        }
    }

    private fun isGateKeyLine(line: String, gate: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith(GATE_KEY_PREFIX)) return false
        val value = trimmed.removePrefix(GATE_KEY_PREFIX).substringBefore("#").trim()
        return value == gate
    }

    private fun enforcedNear(lines: List<String>, anchor: Int): String? {
        val from = (anchor - SEARCH_WINDOW).coerceAtLeast(0)
        val to = (anchor + SEARCH_WINDOW).coerceAtMost(lines.lastIndex)
        return (from..to)
            .map { i -> i to lines[i].trim() }
            .filter { (_, trimmed) -> trimmed.startsWith(ENFORCED_PREFIX) }
            .minByOrNull { (i, _) -> abs(i - anchor) }
            ?.let { (_, trimmed) -> trimmed.removePrefix(ENFORCED_PREFIX).substringBefore("#").trim() }
    }
}
