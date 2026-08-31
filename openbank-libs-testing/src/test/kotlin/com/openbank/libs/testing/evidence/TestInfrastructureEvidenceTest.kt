// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.testing.evidence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Drives the real Quarkus sequence from issue #7640 — several `start()` calls (each from a
 * freshly constructed manager instance, which is why no per-instance flag can see them) before
 * a single terminal `stop()` — and asserts what actually lands in the JSONL.
 *
 * The second test is the control: it proves the dedup did not blind the evidence to the failure
 * mode the stream exists to expose, a resource that starts and never stops.
 */
class TestInfrastructureEvidenceTest {

    @TempDir
    lateinit var evidenceDir: Path

    @BeforeEach
    fun setUp() {
        TestInfrastructureEvidence.resetForTesting()
        System.setProperty("openbank.test.evidence.dir", evidenceDir.toString())
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("openbank.test.evidence.dir")
        TestInfrastructureEvidence.resetForTesting()
    }

    private fun lines(): List<String> {
        val file = evidenceDir.resolve("testcontainers.jsonl")
        return if (Files.exists(file)) Files.readAllLines(file).filter { it.isNotBlank() } else emptyList()
    }

    @Test
    fun `repeated starts of one logical resource collapse to a single lifecycle carrying the reprovision count`() {
        val image = "postgres:16.3-alpine"
        TestInfrastructureEvidence.record("postgres", image, "started", Instant.parse("2026-08-31T10:00:00Z"))
        TestInfrastructureEvidence.record("postgres", image, "started", Instant.parse("2026-08-31T10:01:00Z"))
        TestInfrastructureEvidence.record("postgres", image, "started", Instant.parse("2026-08-31T10:02:00Z"))
        TestInfrastructureEvidence.record("postgres", image, "stopped", Instant.parse("2026-08-31T10:03:00Z"))

        val emitted = lines()
        assertThat(emitted).hasSize(2)
        assertThat(emitted[0]).contains("\"lifecycle\":\"started\"")
        assertThat(emitted[0]).doesNotContain("reprovisions")
        assertThat(emitted[1]).contains("\"lifecycle\":\"stopped\"")
        // The two suppressed physical provisions are counted, not discarded.
        assertThat(emitted[1]).contains("\"reprovisions\":2")
    }

    @Test
    fun `a distinct image is a distinct logical lifecycle`() {
        TestInfrastructureEvidence.record("postgres", "postgres:16.3-alpine", "started")
        TestInfrastructureEvidence.record("postgres", "postgres:17-alpine", "started")

        assertThat(lines()).hasSize(2)
    }

    @Test
    fun `a resource that starts and never stops is still recorded as an unterminated lifecycle`() {
        TestInfrastructureEvidence.record("redpanda", "redpandadata/redpanda:v24.1.1", "started")

        val emitted = lines()
        assertThat(emitted).hasSize(1)
        assertThat(emitted.single()).contains("\"lifecycle\":\"started\"")
        assertThat(emitted.none { it.contains("\"lifecycle\":\"stopped\"") }).isTrue()
    }

    @Test
    fun `a fresh lifecycle after a stop is recorded again`() {
        val image = "postgres:16.3-alpine"
        TestInfrastructureEvidence.record("postgres", image, "started")
        TestInfrastructureEvidence.record("postgres", image, "stopped")
        TestInfrastructureEvidence.record("postgres", image, "started")

        val emitted = lines()
        assertThat(emitted).hasSize(3)
        assertThat(emitted.map { it.substringAfter("\"lifecycle\":\"").substringBefore("\"") })
            .containsExactly("started", "stopped", "started")
    }
}
