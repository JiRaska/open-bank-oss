// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The customer channel's shared-store boot gate, proved on the REAL production artefact (review of
 * #16791): the built fast-jar launched as a child process, so Quarkus runs in `LaunchMode.NORMAL`
 * with its real CDI container and its real Redis extension — no mocked `Instance`.
 *
 * Postgres and an allowlist are supplied because Flyway and the allowlist check both run BEFORE the
 * store gate, so without them a refusal would be theirs and prove nothing (the first run of this IT,
 * without the allowlist, failed all four cases on exactly that — the message assertion caught it). Every refusal case asserts the guard's OWN message,
 * and the positive control (valkey + hosts + secret) must reach "started", so a launch that dies for
 * some unrelated reason cannot read as the guard working.
 *
 * Runs from the `customerBootGuardIT` task (needs `quarkusBuild`), not from `test`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerChannelBootGuardIT {
    private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("openbank_mcp")
        .withUsername("openbank")
        .withPassword("boot-guard-test-only")
    private val valkey: GenericContainer<*> = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine"))
        .withExposedPorts(6379)
        .withCommand("valkey-server", "--requirepass", VALKEY_PASSWORD)
    private val runner: String = System.getProperty("openbank.test.mcp.runner")
        ?: error("openbank.test.mcp.runner is not set — run the customerBootGuardIT task")

    private val allowlist: File by lazy {
        Files.createTempFile("mcp-client-allowlist", ".yaml").toFile().apply {
            writeText(
                CustomerChannelBootGuardIT::class.java.classLoader.getResource(
                    "test-mcp-client-allowlist.yaml",
                )!!.readText(),
            )
        }
    }

    @BeforeAll
    fun start() {
        DockerRequirement.require("CustomerChannelBootGuardIT")
        postgres.start()
        valkey.start()
    }

    @AfterAll
    fun stop() {
        allowlist.delete()
        if (postgres.isRunning) postgres.stop()
        if (valkey.isRunning) valkey.stop()
    }

    private class Run(val exited: Boolean, val exitCode: Int?, val output: String)

    /**
     * Launches the fast-jar; returns once it exits, or once [readyMarker] appears. The child never
     * outlives the call (destroyForcibly in `finally`), its log is deleted, and the environment is
     * cleared of every inherited variable this test controls, so a developer's or CI runner's
     * `MCP_*` / `QUARKUS_REDIS_*` / `QUARKUS_DATASOURCE_*` cannot decide the outcome.
     */
    private fun launch(extraEnv: Map<String, String>, readyMarker: String? = null): Run {
        val log = Files.createTempFile("mcp-boot-guard", ".log").toFile()
        val pb = ProcessBuilder(javaBin(), "-jar", runner).redirectErrorStream(true).redirectOutput(log)
        pb.environment().apply {
            keys.removeIf { k -> INHERITED_PREFIXES.any { k.startsWith(it) } || k == "POSTGRES_PASSWORD" }
            put("QUARKUS_DATASOURCE_JDBC_URL", postgres.jdbcUrl)
            put(
                "QUARKUS_DATASOURCE_REACTIVE_URL",
                "postgresql://${postgres.host}:${postgres.firstMappedPort}/openbank_mcp",
            )
            put("QUARKUS_DATASOURCE_USERNAME", postgres.username)
            put("POSTGRES_PASSWORD", postgres.password)
            put("QUARKUS_HTTP_PORT", "0")
            put("QUARKUS_MANAGEMENT_PORT", "0")
            put("MCP_CUSTOMER_ENABLED", "true")
            // No allowlist ships in the artefact; without one the allowlist check (which runs first)
            // would be the refusal, and these cases would prove nothing about the store gate.
            put("MCP_CUSTOMER_ALLOWLIST", allowlist.absolutePath)
            putAll(extraEnv)
        }
        val process = pb.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOOT_SECONDS)
            while (System.nanoTime() < deadline) {
                if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                    return Run(true, process.exitValue(), log.readText())
                }
                if (readyMarker != null && log.readText().contains(readyMarker)) return Run(false, null, log.readText())
            }
            return Run(false, null, log.readText())
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(BOOT_SECONDS, TimeUnit.SECONDS)
            }
            log.delete()
        }
    }

    private fun javaBin() = File(System.getProperty("java.home"), "bin/java").path

    private fun assertRefused(run: Run, message: String) {
        assertThat(run.exited).`as`("the service must refuse to boot:\n%s", run.output.takeLast(TAIL)).isTrue()
        assertThat(run.exitCode).isNotZero()
        assertThat(run.output).contains(message)
    }

    @Test
    fun `valkey with no quarkus_redis_hosts refuses to boot in a production launch`() {
        assertRefused(
            launch(mapOf("MCP_CUSTOMER_SHARED_STORE" to "valkey", "MCP_CUSTOMER_KEY_HMAC_SECRET" to SECRET)),
            "no Redis client is configured",
        )
    }

    @Test
    fun `memory refuses to boot in a production launch`() {
        assertRefused(
            launch(mapOf("MCP_CUSTOMER_SHARED_STORE" to "memory")),
            "requires mcp.customer.shared-store=valkey",
        )
    }

    @Test
    fun `valkey without the key HMAC secret refuses to boot`() {
        assertRefused(
            launch(
                mapOf(
                    "MCP_CUSTOMER_SHARED_STORE" to "valkey",
                    "QUARKUS_REDIS_HOSTS" to valkeyUrl(),
                    "QUARKUS_REDIS_PASSWORD" to VALKEY_PASSWORD,
                ),
            ),
            "key-hmac-secret is not set",
        )
    }

    @Test
    fun `valkey without a password refuses to boot`() {
        assertRefused(
            launch(
                mapOf(
                    "MCP_CUSTOMER_SHARED_STORE" to "valkey",
                    "QUARKUS_REDIS_HOSTS" to valkeyUrl(),
                    "MCP_CUSTOMER_KEY_HMAC_SECRET" to SECRET,
                ),
            ),
            "no Valkey password is configured",
        )
    }

    @Test
    fun `positive control - valkey with hosts and secret boots with the shared store`() {
        val run = launch(
            mapOf(
                "MCP_CUSTOMER_SHARED_STORE" to "valkey",
                "QUARKUS_REDIS_HOSTS" to valkeyUrl(),
                "QUARKUS_REDIS_PASSWORD" to VALKEY_PASSWORD,
                "MCP_CUSTOMER_KEY_HMAC_SECRET" to SECRET,
            ),
            readyMarker = "started in",
        )
        assertThat(run.exited).`as`("must stay up:\n%s", run.output.takeLast(TAIL)).isFalse()
        assertThat(run.output).contains("customer MCP channel enabled").contains("ValkeyDpopReplayStore")
    }

    private fun valkeyUrl() = "redis://${valkey.host}:${valkey.firstMappedPort}"

    private companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef-test-only"
        const val VALKEY_PASSWORD = "boot-guard-valkey-test-only"
        val INHERITED_PREFIXES = listOf("MCP_", "QUARKUS_REDIS_", "QUARKUS_DATASOURCE_")
        const val BOOT_SECONDS = 90L
        const val POLL_MS = 250L
        const val TAIL = 4000
    }
}
