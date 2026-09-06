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
 * The degradation paths of [RepoStateReadAdapter] — absent root, absent or malformed registry
 * files, and directories that only look like modules. Each of these must produce an EMPTY reading
 * rather than an exception that would abort the whole sweep, and must not invent a module.
 */
class RepoStateReadAdapterEdgeCasesTest {

    private fun adapterFor(root: Path): RepoStateReadAdapter {
        val config = mockk<ReleaseStewardConfig> {
            every { repoRoot() } returns root.toString()
        }
        return RepoStateReadAdapter(config, ObjectMapper())
    }

    @Test
    fun `a repo root that does not exist yields an entirely empty snapshot, not an exception`(
        @TempDir tmp: Path,
    ): Unit = runBlocking {
        val snapshot = adapterFor(tmp.resolve("no-such-checkout")).snapshot()

        assertThat(snapshot.modulesWithVersionTxt).isEmpty()
        assertThat(snapshot.releasePleaseConfigPackages).isEmpty()
        assertThat(snapshot.releasePleaseManifestKeys).isEmpty()
        assertThat(snapshot.adminUiPackageJsonVersion).isNull()
        assertThat(snapshot.adminUiVersionTxt).isNull()
        assertThat(snapshot.servicesWithVersionOverride).isEmpty()
    }

    @Test
    fun `malformed registry JSON degrades to an empty set rather than aborting the sweep`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        Files.writeString(root.resolve("release-please-config.json"), "{ this is not json")
        Files.writeString(root.resolve(".release-please-manifest.json"), "{ neither is this")

        val snapshot = adapterFor(root).snapshot()

        assertThat(snapshot.releasePleaseConfigPackages).isEmpty()
        assertThat(snapshot.releasePleaseManifestKeys).isEmpty()
    }

    @Test
    fun `a config file with no packages node yields an empty package set`(@TempDir root: Path): Unit = runBlocking {
        Files.writeString(root.resolve("release-please-config.json"), "{\"release-type\": \"simple\"}")

        assertThat(adapterFor(root).snapshot().releasePleaseConfigPackages).isEmpty()
    }

    @Test
    fun `a directory not prefixed openbank- is never a module, even with a version-txt`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        Files.createDirectories(root.resolve("docs"))
        Files.writeString(root.resolve("docs/version.txt"), "9.9.9\n")
        Files.createDirectories(root.resolve("openbank-ledger-service"))
        Files.writeString(root.resolve("openbank-ledger-service/version.txt"), "1.0.0\n")

        assertThat(adapterFor(root).snapshot().modulesWithVersionTxt)
            .containsExactly("openbank-ledger-service")
    }

    @Test
    fun `an openbank- directory WITHOUT a version-txt is not a released component`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        Files.createDirectories(root.resolve("openbank-libs-domain"))

        assertThat(adapterFor(root).snapshot().modulesWithVersionTxt).isEmpty()
    }

    @Test
    fun `a top-level version-txt FILE named like a module is not mistaken for a directory`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        Files.writeString(root.resolve("openbank-not-a-dir"), "x")

        assertThat(adapterFor(root).snapshot().modulesWithVersionTxt).isEmpty()
    }

    @Test
    fun `admin-ui package-json without a version field reads as null, not as an empty string`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        Files.createDirectories(root.resolve("openbank-admin-ui"))
        Files.writeString(root.resolve("openbank-admin-ui/package.json"), "{\"name\": \"admin-ui\"}")

        assertThat(adapterFor(root).snapshot().adminUiPackageJsonVersion).isNull()
    }

    @Test
    fun `a malformed admin-ui package-json degrades to null instead of throwing`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        Files.createDirectories(root.resolve("openbank-admin-ui"))
        Files.writeString(root.resolve("openbank-admin-ui/package.json"), "not json at all")

        assertThat(adapterFor(root).snapshot().adminUiPackageJsonVersion).isNull()
    }

    @Test
    fun `the admin-ui version-txt read is trimmed of its trailing newline`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        Files.createDirectories(root.resolve("openbank-admin-ui"))
        Files.writeString(root.resolve("openbank-admin-ui/version.txt"), "  0.91.4\n")

        assertThat(adapterFor(root).snapshot().adminUiVersionTxt).isEqualTo("0.91.4")
    }

    @Test
    fun `a module with no application-yaml at all is not scanned as an override`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        Files.createDirectories(root.resolve("openbank-ledger-service"))
        Files.writeString(root.resolve("openbank-ledger-service/version.txt"), "1.0.0\n")

        assertThat(adapterFor(root).snapshot().servicesWithVersionOverride).isEmpty()
    }

    @Test
    fun `a commented-out override and a blank-line-separated block are handled by the scanner`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val yaml = """
            quarkus:

              application:
                name: ledger
            #    version: 9.9.9
        """.trimIndent()
        writeService(root, "openbank-ledger-service", yaml)

        assertThat(adapterFor(root).snapshot().servicesWithVersionOverride).isEmpty()
    }

    @Test
    fun `a blank line inside the quarkus application block does not reset the scanner state`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val yaml = """
            quarkus:
              application:
                name: ledger

                version: 9.9.9
        """.trimIndent()
        writeService(root, "openbank-ledger-service", yaml)

        assertThat(adapterFor(root).snapshot().servicesWithVersionOverride)
            .containsExactly("openbank-ledger-service")
    }

    @Test
    fun `only the services that actually override are listed, in sorted order`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val overriding = "quarkus:\n  application:\n    version: 9.9.9\n"
        val clean = "quarkus:\n  application:\n    name: x\n"
        writeService(root, "openbank-zulu", overriding)
        writeService(root, "openbank-alpha", overriding)
        writeService(root, "openbank-mike", clean)

        assertThat(adapterFor(root).snapshot().servicesWithVersionOverride)
            .containsExactly("openbank-alpha", "openbank-zulu")
    }

    private fun writeService(root: Path, module: String, applicationYaml: String) {
        val moduleDir = root.resolve(module)
        Files.createDirectories(moduleDir.resolve("src/main/resources"))
        Files.writeString(moduleDir.resolve("version.txt"), "1.0.0\n")
        Files.writeString(moduleDir.resolve("src/main/resources/application.yaml"), applicationYaml)
    }
}
