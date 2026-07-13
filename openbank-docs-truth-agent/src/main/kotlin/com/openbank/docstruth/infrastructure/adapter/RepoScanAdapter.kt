// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.application.port.out.RepoScanPort
import com.openbank.docstruth.domain.model.AdrDeliveryStatus
import com.openbank.docstruth.domain.model.AdrRecord
import com.openbank.docstruth.domain.model.ArtifactExistence
import com.openbank.docstruth.domain.model.ClaimedArtifact
import com.openbank.docstruth.domain.model.ClaimedEnforcement
import com.openbank.docstruth.infrastructure.config.DocsTruthAgentConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Direct repo-checkout read of every ADR under `docs/adr` (`read.governance`) and a repo-wide
 * grep/find for the artifacts those ADRs claim (ADR-0166 checks 1-2). Deliberately shallow —
 * existence and basic wiring, not deep semantic verification — mirroring release-steward's own
 * `RepoStateReadAdapter` precedent of real, best-effort file/grep logic rather than a stub, since
 * this agent runs from within the monorepo.
 */
@ApplicationScoped
class RepoScanAdapter(private val config: DocsTruthAgentConfig) : RepoScanPort {

    private val log = Logger.getLogger(RepoScanAdapter::class.java)

    override suspend fun scanAdrRecords(): List<AdrRecord> {
        val adrDir = Path.of(config.repoRoot()).resolve("docs/adr")
        if (!adrDir.exists() || !adrDir.isDirectory()) return emptyList()
        log.infof("Scanning ADR Delivery-Status claims under %s", adrDir.toAbsolutePath())
        return Files.list(adrDir).use { children ->
            children.asSequence()
                .filter { it.isRegularFile() && AdrTextScanner.ADR_FILENAME.matches(it.name) }
                .mapNotNull { parseAdr(it) }
                .sortedBy { it.id }
                .toList()
        }
    }

    private fun parseAdr(file: Path): AdrRecord? {
        val text = runCatching { file.readText() }.getOrElse { e ->
            log.warnf(e, "Failed to read %s", file)
            return null
        }
        return AdrTextScanner.parse(file.name, text)
    }

    override suspend fun findArtifacts(artifacts: Set<String>): Map<String, ArtifactExistence> {
        if (artifacts.isEmpty()) return emptyMap()
        val root = Path.of(config.repoRoot())
        if (!root.exists()) {
            return artifacts.associateWith {
                ArtifactExistence(it, exists = false, matchedPaths = emptyList())
            }
        }
        log.infof("Scanning repo for %d claimed artifact(s) referenced across docs/adr/*.md", artifacts.size)
        val matches = artifacts.associateWith { mutableListOf<String>() }
        Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() }
                .filter { !RepoFileFilter.isExcluded(root, it) }
                .filter { RepoFileFilter.isSearchable(it) }
                .forEach { file -> scanFileForArtifacts(root, file, artifacts, matches) }
        }
        return artifacts.associateWith { artifact ->
            val paths = matches.getValue(artifact)
            ArtifactExistence(artifact = artifact, exists = paths.isNotEmpty(), matchedPaths = paths.toList())
        }
    }

    private fun scanFileForArtifacts(
        root: Path,
        file: Path,
        artifacts: Set<String>,
        matches: Map<String, MutableList<String>>,
    ) {
        val size = runCatching { Files.size(file) }.getOrElse { return }
        if (size > RepoFileFilter.MAX_FILE_BYTES) return
        val content = runCatching { file.readText() }.getOrElse { return }
        val rel = root.relativize(file).toString()
        for (artifact in artifacts) {
            if (content.contains(artifact)) {
                matches.getValue(artifact).add(rel)
            }
        }
    }
}

/** File-tree filtering rules for [RepoScanAdapter.findArtifacts] — split out to keep that
 * adapter's own function count within the fleet's `TooManyFunctions` detekt threshold. */
private object RepoFileFilter {
    const val MAX_FILE_BYTES = 2_000_000L

    private val EXCLUDED_DIR_NAMES = setOf(
        ".git", "build", "node_modules", ".gradle", "dist", ".next", "out", ".idea", ".terraform",
    )
    private val SEARCHABLE_EXTENSIONS = setOf(
        "kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "sh", "yaml", "yml", "json", "md",
        "gradle", "rego", "toml", "sql",
    )

    // Excludes docs/adr/* itself so an artifact is never "found" merely by matching the same ADR
    // prose that quoted it in the first place.
    fun isExcluded(root: Path, file: Path): Boolean {
        val rel = root.relativize(file)
        if (rel.nameCount > 1 && rel.getName(0).toString() == "docs" && rel.getName(1).toString() == "adr") {
            return true
        }
        return rel.any { it.toString() in EXCLUDED_DIR_NAMES }
    }

    fun isSearchable(file: Path): Boolean = file.extension in SEARCHABLE_EXTENSIONS
}

/**
 * Standalone (non-CDI) ADR text parser — split out of [RepoScanAdapter] for the same
 * `TooManyFunctions` reason as `RepoFileFilter`. Extracts the `Delivery-Status:` line and every
 * backtick-quoted artifact/gate reference, tagged with its textual context.
 */
private object AdrTextScanner {
    val ADR_FILENAME = Regex("""^(\d{4})-.*\.md$""")

    private val BACKTICK_TOKEN = Regex("`([^`\\s]{3,80})`")
    private val PASCAL_CLASS = Regex("^[A-Z][A-Za-z0-9]{2,60}$")
    private val PATH_OR_SCRIPT_SUFFIX = Regex(""".*\.(sh|py|kt|kts|ts|tsx|yaml|yml|json)$""")
    private val NOT_YET_PHRASES = listOf(
        "not yet implemented", "not yet built", "not implemented", "not built",
        "does not exist", "no implementation", "pending implementation",
        "hasn't been built", "yet to be built", "not yet wired", "not yet shipped",
    )

    fun parse(fileName: String, text: String): AdrRecord? {
        val match = ADR_FILENAME.find(fileName) ?: return null
        val id = "ADR-${match.groupValues[1]}"
        val title = text.lineSequence().firstOrNull { it.startsWith("# ADR-") }
            ?.substringAfter("—")?.trim()?.ifBlank { null } ?: id
        val deliveryStatus = parseDeliveryStatus(text)
        val (artifacts, enforcements) = scanReferences(text)
        return AdrRecord(
            id = id,
            path = "docs/adr/$fileName",
            title = title,
            deliveryStatus = deliveryStatus,
            claimedArtifacts = artifacts.map { (name, notYet) -> ClaimedArtifact(name, notYet) },
            claimedEnforcements = enforcements.map { (name, enforced) -> ClaimedEnforcement(name, enforced) },
        )
    }

    private fun parseDeliveryStatus(text: String): AdrDeliveryStatus {
        val line = text.lineSequence().firstOrNull { it.startsWith("Delivery-Status:") }
            ?: return AdrDeliveryStatus.NOT_TRACKED
        val value = line.removePrefix("Delivery-Status:")
            .substringBefore("<!--")
            .substringBefore("—")
            .trim()
            .uppercase()
        return runCatching { AdrDeliveryStatus.valueOf(value) }.getOrDefault(AdrDeliveryStatus.NOT_TRACKED)
    }

    private fun scanReferences(text: String): Pair<Map<String, Boolean>, Map<String, Boolean>> {
        val artifacts = LinkedHashMap<String, Boolean>()
        val enforcements = LinkedHashMap<String, Boolean>()
        for (line in text.lineSequence()) {
            val lower = line.lowercase()
            for (m in BACKTICK_TOKEN.findAll(line)) {
                val token = m.groupValues[1]
                if (!looksLikeArtifact(token)) continue
                recordArtifact(artifacts, token, lower)
                recordEnforcement(enforcements, token, lower)
            }
        }
        return artifacts to enforcements
    }

    private fun recordArtifact(artifacts: MutableMap<String, Boolean>, token: String, lowerLine: String) {
        val notYet = NOT_YET_PHRASES.any { lowerLine.contains(it) }
        if (notYet) {
            artifacts[token] = true
        } else {
            artifacts.putIfAbsent(token, false)
        }
    }

    private fun recordEnforcement(enforcements: MutableMap<String, Boolean>, token: String, lowerLine: String) {
        if (!lowerLine.contains("enforced") && !lowerLine.contains("advisory")) return
        enforcements[token] = !lowerLine.contains("advisory")
    }

    private fun looksLikeArtifact(token: String): Boolean =
        PASCAL_CLASS.matches(token) || token.contains("/") || PATH_OR_SCRIPT_SUFFIX.matches(token)
}
