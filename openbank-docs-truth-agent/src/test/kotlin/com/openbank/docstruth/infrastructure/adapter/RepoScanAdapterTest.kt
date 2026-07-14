// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.infrastructure.config.DocsTruthAgentConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class RepoScanAdapterTest {

    @TempDir
    lateinit var repoDir: Path

    private fun adapter(): RepoScanAdapter {
        val config = mockk<DocsTruthAgentConfig>()
        every { config.repoRoot() } returns repoDir.toString()
        return RepoScanAdapter(config)
    }

    // NOTE: every test body below uses a block body (`{ runBlocking { ... } }`), never
    // `fun f() = runBlocking { ... }`. The expression-body form infers the function's return type
    // from runBlocking's result (e.g. an AssertJ assert object), which is non-Unit — JUnit5 then
    // silently skips the "test method must not return a value" method instead of running it.

    @Test
    fun `scanAdrRecords returns an empty list when docs adr does not exist`() {
        runBlocking {
            val records = adapter().scanAdrRecords()
            assertThat(records).isEmpty()
        }
    }

    @Test
    fun `scanAdrRecords parses every matching ADR file and skips non-conforming filenames`() {
        runBlocking {
            val adrDir = File(repoDir.toFile(), "docs/adr").apply { mkdirs() }
            File(adrDir, "0001-first-decision.md").writeText("Delivery-Status: Shipped\n")
            File(adrDir, "0002-second-decision.md").writeText("Delivery-Status: Planned\n")
            File(adrDir, "README.md").writeText("not an ADR, must be ignored by ADR_FILENAME\n")

            val records = adapter().scanAdrRecords()

            assertThat(records.map { it.id }).containsExactly("ADR-0001", "ADR-0002")
        }
    }

    @Test
    fun `findArtifacts locates an artifact only in a searchable, non-excluded file`() {
        runBlocking {
            File(repoDir.toFile(), "openbank-ledger-service/src/main/kotlin").apply { mkdirs() }
                .resolve("LedgerService.kt")
                .writeText("class LedgerService")
            // Excluded build-output directory: must not count as a match even though it names the
            // same artifact, since it's generated/derived content, not source.
            File(repoDir.toFile(), "openbank-ledger-service/build/classes").apply { mkdirs() }
                .resolve("LedgerService.class.txt")
                .writeText("LedgerService")

            val result = adapter().findArtifacts(setOf("LedgerService"))

            val existence = result.getValue("LedgerService")
            assertThat(existence.exists).isTrue()
            assertThat(existence.matchedPaths).hasSize(1)
            assertThat(existence.matchedPaths.single()).contains("src/main/kotlin/LedgerService.kt")
        }
    }

    @Test
    fun `findArtifacts excludes docs adr itself so an ADR never self-confirms its own claim`() {
        runBlocking {
            val adrDir = File(repoDir.toFile(), "docs/adr").apply { mkdirs() }
            File(adrDir, "0039-example.md").writeText("`GhostArtifact` is claimed here but nowhere else.\n")

            val result = adapter().findArtifacts(setOf("GhostArtifact"))

            assertThat(result.getValue("GhostArtifact").exists).isFalse()
        }
    }

    @Test
    fun `findArtifacts ignores a non-searchable file extension`() {
        runBlocking {
            File(repoDir.toFile(), "assets").apply { mkdirs() }
                .resolve("notes.png")
                .writeText("BinaryLookingArtifact")

            val result = adapter().findArtifacts(setOf("BinaryLookingArtifact"))

            assertThat(result.getValue("BinaryLookingArtifact").exists).isFalse()
        }
    }

    @Test
    fun `findArtifacts returns not-found for every artifact when repo root does not exist`() {
        runBlocking {
            val config = mockk<DocsTruthAgentConfig>()
            every { config.repoRoot() } returns repoDir.resolve("does-not-exist").toString()
            val result = RepoScanAdapter(config).findArtifacts(setOf("Anything"))
            assertThat(result.getValue("Anything").exists).isFalse()
        }
    }
}
