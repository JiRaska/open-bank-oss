// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.releasesteward.application.port.out.RepoStateReadPort
import com.openbank.releasesteward.domain.model.RepoStateSnapshot
import com.openbank.releasesteward.infrastructure.config.ReleaseStewardConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Direct repo-checkout read of the release/version-axis state (`read.governance`, ADR-0165 checks
 * 1-3). Unlike `GitHubOpenPrReadAdapter` (a remote API), this reads local files against a repo
 * checkout mounted at `openbank.release-steward.repo-root` — this agent runs from within the
 * monorepo, so a full re-implementation of a GitHub client is not needed for these three checks.
 *
 * Mirrors, best-effort, the exact logic of the three CI scripts this agent proactively duplicates
 * fleet-wide (`openbank-infra/scripts/check-release-registration.py`,
 * `.github/scripts/check-admin-ui-version-sync.sh`, `.github/scripts/check-app-version-override.sh`)
 * — those scripts remain the CI-enforced source of truth; this adapter is an independent
 * re-derivation for proactive, periodic reporting, not a replacement for them.
 */
@ApplicationScoped
class RepoStateReadAdapter(private val config: ReleaseStewardConfig, private val objectMapper: ObjectMapper) :
    RepoStateReadPort {

    private val log = Logger.getLogger(RepoStateReadAdapter::class.java)

    override suspend fun snapshot(): RepoStateSnapshot {
        val root = Path.of(config.repoRoot())
        log.infof("Reading release/version-axis repo state from %s", root.toAbsolutePath())

        val modulesWithVersionTxt = scanModulesWithVersionTxt(root)
        return RepoStateSnapshot(
            releasePleaseConfigPackages = readConfigPackages(root),
            releasePleaseManifestKeys = readManifestKeys(root),
            modulesWithVersionTxt = modulesWithVersionTxt,
            adminUiPackageJsonVersion = readAdminUiPackageJsonVersion(root),
            adminUiVersionTxt = readVersionTxt(root.resolve("openbank-admin-ui")),
            servicesWithVersionOverride = scanAppVersionOverrides(root, modulesWithVersionTxt),
        )
    }

    private fun readConfigPackages(root: Path): Set<String> {
        val file = root.resolve("release-please-config.json")
        if (!file.exists()) return emptySet()
        return runCatching {
            objectMapper.readTree(file.toFile()).get("packages")?.fieldNames()?.asSequence()?.toSet()
                ?: emptySet()
        }.getOrElse { e ->
            log.warnf(e, "Failed to parse %s", file)
            emptySet()
        }
    }

    private fun readManifestKeys(root: Path): Set<String> {
        val file = root.resolve(".release-please-manifest.json")
        if (!file.exists()) return emptySet()
        return runCatching {
            objectMapper.readTree(file.toFile()).fieldNames().asSequence().toSet()
        }.getOrElse { e ->
            log.warnf(e, "Failed to parse %s", file)
            emptySet()
        }
    }

    // A module is a released component IFF it has a version.txt directly under its top-level
    // directory (rules.yaml: released_unit_marker) — the same definition
    // check-release-registration.py uses.
    private fun scanModulesWithVersionTxt(root: Path): Set<String> {
        if (!root.exists()) return emptySet()
        return Files.list(root).use { children ->
            children.asSequence()
                .filter { it.isDirectory() && it.name.startsWith("openbank-") }
                .filter { it.resolve("version.txt").exists() }
                .map { it.name }
                .toSet()
        }
    }

    private fun readAdminUiPackageJsonVersion(root: Path): String? {
        val file = root.resolve("openbank-admin-ui/package.json")
        if (!file.exists()) return null
        return runCatching {
            objectMapper.readTree(file.toFile()).get("version")?.asText()
        }.getOrElse { e ->
            log.warnf(e, "Failed to parse %s", file)
            null
        }
    }

    private fun readVersionTxt(moduleDir: Path): String? {
        val file = moduleDir.resolve("version.txt")
        if (!file.exists()) return null
        return runCatching { file.readText().trim() }.getOrElse { e ->
            log.warnf(e, "Failed to read %s", file)
            null
        }
    }

    // Best-effort port of check-app-version-override.sh's awk state machine: flags a
    // `    version:` line nested exactly under `quarkus: -> application:` — the shadowing pattern
    // ADR-0165 incident 3 is grounded in. The CI script remains the enforced gate; this is a
    // proactive fleet-wide re-derivation of the same check.
    private fun scanAppVersionOverrides(root: Path, modules: Set<String>): List<String> =
        modules.sorted().filter { module ->
            val appYaml = root.resolve("$module/src/main/resources/application.yaml")
            appYaml.exists() &&
                runCatching { AppVersionOverrideScanner.hasOverride(appYaml.readText()) }.getOrElse { e ->
                    log.warnf(e, "Failed to scan %s", appYaml)
                    false
                }
        }
}

/**
 * Standalone (non-CDI) YAML state machine mirroring check-app-version-override.sh's awk logic —
 * split out of [RepoStateReadAdapter] to keep that adapter's own function count within the
 * fleet's `TooManyFunctions` detekt threshold.
 */
private object AppVersionOverrideScanner {

    private enum class ScanState { OUTSIDE, IN_QUARKUS, IN_APPLICATION }

    fun hasOverride(text: String): Boolean {
        var state = ScanState.OUTSIDE
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            state = nextState(state, line)
            if (state == ScanState.IN_APPLICATION && line.startsWith("    version:")) return true
        }
        return false
    }

    private fun nextState(state: ScanState, line: String): ScanState = when {
        isTopLevelKey(line) -> if (line.startsWith("quarkus:")) ScanState.IN_QUARKUS else ScanState.OUTSIDE
        state == ScanState.OUTSIDE -> ScanState.OUTSIDE
        isSecondLevelKey(
            line,
        ) -> if (line.startsWith("  application:")) ScanState.IN_APPLICATION else ScanState.IN_QUARKUS
        else -> state
    }

    // A top-level YAML mapping key: column 0, not a continuation/comment line.
    private fun isTopLevelKey(line: String): Boolean {
        val first = line[0]
        return first != ' ' && first != '\t' && first != '#'
    }

    // A key nested exactly one level (2-space indent) under a top-level key.
    private fun isSecondLevelKey(line: String): Boolean {
        if (!line.startsWith("  ")) return false
        val third = line.getOrNull(2) ?: return false
        return third != ' ' && third != '#'
    }
}
