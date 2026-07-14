// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.releasesteward.infrastructure.config.ReleaseStewardConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises [RepoStateReadAdapter] against real files under a temp-dir checkout fixture, rather
 * than mocking file IO — this is the only way to also cover the file-private
 * `AppVersionOverrideScanner` state machine (checks 1-3, ADR-0165 incidents 1-3), since it has no
 * public surface of its own to unit-test directly.
 */
class RepoStateReadAdapterTest {

    private val objectMapper = ObjectMapper()

    private fun adapterFor(root: Path): RepoStateReadAdapter {
        val config = mockk<ReleaseStewardConfig> {
            every { repoRoot() } returns root.toString()
        }
        return RepoStateReadAdapter(config, objectMapper)
    }

    private fun writeModule(root: Path, module: String, applicationYaml: String? = null) {
        val moduleDir = root.resolve(module)
        Files.createDirectories(moduleDir)
        Files.writeString(moduleDir.resolve("version.txt"), "1.0.0\n")
        if (applicationYaml != null) {
            val resourcesDir = moduleDir.resolve("src/main/resources")
            Files.createDirectories(resourcesDir)
            Files.writeString(resourcesDir.resolve("application.yaml"), applicationYaml)
        }
    }

    private fun writeRegistration(root: Path, configPackages: Set<String>, manifestKeys: Set<String>) {
        val packagesJson = configPackages.joinToString(",") { "\"$it\": {}" }
        Files.writeString(
            root.resolve("release-please-config.json"),
            "{\"packages\": {$packagesJson}}",
        )
        val manifestJson = manifestKeys.joinToString(",") { "\"$it\": \"1.0.0\"" }
        Files.writeString(root.resolve(".release-please-manifest.json"), "{$manifestJson}")
    }

    @Test
    fun `matching case - module has version-txt, config entry and manifest entry all agree`(@TempDir root: Path) {
        writeModule(root, "openbank-ledger-service")
        writeRegistration(
            root,
            configPackages = setOf("openbank-ledger-service"),
            manifestKeys = setOf("openbank-ledger-service"),
        )

        val snapshot = runBlocking { adapterFor(root).snapshot() }

        assertThat(snapshot.modulesWithVersionTxt).containsExactly("openbank-ledger-service")
        assertThat(snapshot.releasePleaseConfigPackages).containsExactly("openbank-ledger-service")
        assertThat(snapshot.releasePleaseManifestKeys).containsExactly("openbank-ledger-service")
    }

    @Test
    fun `missing entry - module has version-txt but is absent from config and manifest`(@TempDir root: Path) {
        writeModule(root, "openbank-unregistered-service")
        writeRegistration(root, configPackages = emptySet(), manifestKeys = emptySet())

        val snapshot = runBlocking { adapterFor(root).snapshot() }

        assertThat(snapshot.modulesWithVersionTxt).containsExactly("openbank-unregistered-service")
        assertThat(snapshot.releasePleaseConfigPackages).isEmpty()
        assertThat(snapshot.releasePleaseManifestKeys).isEmpty()
    }

    @Test
    fun `orphan entry - config and manifest register a module that has no version-txt`(@TempDir root: Path) {
        writeRegistration(
            root,
            configPackages = setOf("openbank-ghost-service"),
            manifestKeys = setOf("openbank-ghost-service"),
        )

        val snapshot = runBlocking { adapterFor(root).snapshot() }

        assertThat(snapshot.modulesWithVersionTxt).isEmpty()
        assertThat(snapshot.releasePleaseConfigPackages).containsExactly("openbank-ghost-service")
        assertThat(snapshot.releasePleaseManifestKeys).containsExactly("openbank-ghost-service")
    }

    @Test
    fun `admin-ui version fields are read from package-json and version-txt`(@TempDir root: Path) {
        val adminUiDir = root.resolve("openbank-admin-ui")
        Files.createDirectories(adminUiDir)
        Files.writeString(adminUiDir.resolve("package.json"), "{\"version\": \"3.2.1\"}")
        Files.writeString(adminUiDir.resolve("version.txt"), "3.2.1\n")
        writeRegistration(root, configPackages = emptySet(), manifestKeys = emptySet())

        val snapshot = runBlocking { adapterFor(root).snapshot() }

        assertThat(snapshot.adminUiPackageJsonVersion).isEqualTo("3.2.1")
        assertThat(snapshot.adminUiVersionTxt).isEqualTo("3.2.1")
    }

    @Test
    fun `nested quarkus application version override is detected (ADR-0165 incident 3)`(@TempDir root: Path) {
        writeModule(
            root,
            "openbank-drifted-service",
            applicationYaml = """
                quarkus:
                  application:
                    name: openbank-drifted-service
                    version: 0.1.0
            """.trimIndent(),
        )
        writeRegistration(root, configPackages = emptySet(), manifestKeys = emptySet())

        val snapshot = runBlocking { adapterFor(root).snapshot() }

        assertThat(snapshot.servicesWithVersionOverride).containsExactly("openbank-drifted-service")
    }

    @Test
    fun `a version key under an unrelated top-level block is a decoy and does NOT trigger`(@TempDir root: Path) {
        writeModule(
            root,
            "openbank-clean-service",
            applicationYaml = """
                openbank:
                  application:
                    version: 0.1.0
                quarkus:
                  application:
                    name: openbank-clean-service
            """.trimIndent(),
        )
        writeRegistration(root, configPackages = emptySet(), manifestKeys = emptySet())

        val snapshot = runBlocking { adapterFor(root).snapshot() }

        assertThat(snapshot.servicesWithVersionOverride).isEmpty()
    }

    @Test
    fun `a version key directly under quarkus, not under application, does NOT trigger`(@TempDir root: Path) {
        writeModule(
            root,
            "openbank-other-service",
            applicationYaml = """
                quarkus:
                  application:
                    name: openbank-other-service
                  http:
                    version: 0.1.0
            """.trimIndent(),
        )
        writeRegistration(root, configPackages = emptySet(), manifestKeys = emptySet())

        val snapshot = runBlocking { adapterFor(root).snapshot() }

        assertThat(snapshot.servicesWithVersionOverride).isEmpty()
    }

    @Test
    fun `a duplicate top-level quarkus block still has its override caught`(@TempDir root: Path) {
        // SmallRye/SnakeYAML keep only the LAST of a duplicate top-level mapping key at runtime
        // (CLAUDE.md's duplicate-YAML-key footgun), but the scanner reads sequentially and must
        // catch an override in EITHER occurrence, since either one is a live bug once merged.
        writeModule(
            root,
            "openbank-dup-block-service",
            applicationYaml = """
                quarkus:
                  application:
                    name: openbank-dup-block-service
                openbank:
                  outbox:
                    dispatch-enabled: true
                quarkus:
                  application:
                    version: 0.1.0
            """.trimIndent(),
        )
        writeRegistration(root, configPackages = emptySet(), manifestKeys = emptySet())

        val snapshot = runBlocking { adapterFor(root).snapshot() }

        assertThat(snapshot.servicesWithVersionOverride).containsExactly("openbank-dup-block-service")
    }
}
