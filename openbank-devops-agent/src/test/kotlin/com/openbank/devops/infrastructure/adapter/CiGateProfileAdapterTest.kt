// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.infrastructure.config.DevOpsConfig
import com.openbank.libs.security.EgressDecision
import com.openbank.libs.security.EgressDenialReason
import com.openbank.libs.security.EgressDeniedException
import com.openbank.libs.security.EgressResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CiGateProfileAdapterTest {
    private val github = mockk<GitHubMetricsAdapter>()
    private val config = mockk<DevOpsConfig>()
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-11-08T08:00:00Z")
    private val source = "a".repeat(40)
    private val ref = "/git/ref/heads/main"
    private val runs = "/actions/workflows/ci.yml/runs?event=push&status=completed&head_sha=$source&per_page=1"
    private val attempt = "/actions/runs/10/attempts/2"
    private val artifacts = "/actions/runs/10/artifacts?per_page=100"
    private val download = "/actions/artifacts/20/zip"
    private val location = "https://archive.example.invalid/signed.zip"
    private val gate = """{"id":"example-gate","group":"lint","mode":"enforced","status":"ok",
        "failure_kind":null,"seconds":3.0,"cpu_seconds":5.0,"budget_seconds":10,
        "selftest_seconds":1.0,"selftest_declared":true,"selftest_passed":null,
        "selftest_skipped":true,"subjects":null,"min_subjects":null,"wall_over_budget":false}"""
    private lateinit var archive: ByteArray
    private lateinit var run: ObjectNode
    private lateinit var artifact: ObjectNode

    private fun adapter() = CiGateProfileAdapter(github, mapper, config, Clock.fixed(now, ZoneOffset.UTC))
    private fun objectNode(value: String) = mapper.readTree(value) as ObjectNode
    private fun response(value: JsonNode) = EgressResponse(200, emptyMap(), mapper.writeValueAsBytes(value))
    private fun response(value: String) = EgressResponse(200, emptyMap(), value.toByteArray())
    private fun digest(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zip(contents: List<Pair<String, String>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            contents.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun publishArchive(bytes: ByteArray) {
        archive = bytes
        artifact.put("digest", digest(bytes))
        artifact.put("size_in_bytes", bytes.size)
        coEvery { github.profileArchive(location) } returns EgressResponse(200, emptyMap(), bytes)
    }

    @BeforeEach
    fun setup() {
        every { config.githubOwner() } returns "example"
        every { config.githubRepo() } returns "repo"
        coEvery { github.profileResponse(any()) } returns null
        coEvery { github.profileArchive(any()) } returns null
        run = objectNode(
            """{"id":10,"run_attempt":2,"path":".github/workflows/ci.yml","event":"push",
                "status":"completed","conclusion":"success","head_branch":"main","head_sha":"$source",
                "updated_at":"${now.minusSeconds(60)}","pull_requests":[],
                "repository":{"id":7,"full_name":"example/repo"},
                "head_repository":{"id":7,"full_name":"example/repo"}}""",
        )
        artifact = objectNode(
            """{"id":20,"name":"gate-results-lint-a2","expired":false,
                "created_at":"${now.minusSeconds(30)}","expires_at":"${now.plusSeconds(3600)}",
                "workflow_run":{"id":10,"repository_id":7,"head_repository_id":7,
                "head_branch":"main","head_sha":"$source"}}""",
        )
        publishArchive(zip(listOf("gate-results-lint.json" to "[$gate]")))
        coEvery { github.profileResponse("") } returns response(
            """{"id":7,"full_name":"example/repo","default_branch":"main"}""",
        )
        coEvery { github.profileResponse(ref) } returns response(
            """{"ref":"refs/heads/main","object":{"type":"commit","sha":"$source"}}""",
        )
        coEvery { github.profileResponse(runs) } answers {
            response(
                mapper.createObjectNode().put(
                    "total_count",
                    1,
                ).set<JsonNode>("workflow_runs", mapper.createArrayNode().add(run)),
            )
        }
        coEvery { github.profileResponse(attempt) } answers { response(run) }
        coEvery { github.profileResponse(artifacts) } answers {
            response(
                mapper.createObjectNode().put(
                    "total_count",
                    1,
                ).set<JsonNode>("artifacts", mapper.createArrayNode().add(artifact)),
            )
        }
        coEvery { github.profileResponse(download) } returns
            EgressResponse(302, mapOf("location" to listOf(location)), byteArrayOf())
    }

    @Test
    fun `verified bytes preserve costs verdicts and immutable provenance as partial coverage`(): Unit = runBlocking {
        val result = adapter().collect()
        assertThat(result.state).isEqualTo(DeliveryEvidenceState.PARTIAL)
        assertThat(result.reason).isEqualTo("SINGLE_ARTIFACT")
        assertThat(result.observedAt).isEqualTo(now)
        assertThat(result.provenance!!.sourceSha).isEqualTo(source)
        assertThat(result.provenance!!.runAttempt).isEqualTo(2)
        assertThat(result.provenance!!.artifactDigest).isEqualTo(digest(archive))
        assertThat(result.provenance!!.artifactCreatedAt).isEqualTo(now.minusSeconds(30))
        val observed = result.gates.single()
        assertThat(observed.cpuSeconds).isEqualTo(5.0) // Parallel children may consume more CPU than wall.
        assertThat(observed.wallSeconds).isEqualTo(3.0)
        assertThat(observed.selftestSkipped).isTrue()
        assertThat(observed.selftestPassed).isNull()
        assertThat(observed.subjects).isNull()
        coVerify(exactly = 2) { github.profileResponse(ref) }
        coVerify(exactly = 1) { github.profileResponse(download) }
        coVerify(exactly = 1) { github.profileArchive(location) }
    }

    @Test
    fun `missing evidence remains unavailable with no fabricated zero or provenance`(): Unit = runBlocking {
        coEvery { github.profileResponse("") } returns null
        val result = adapter().collect()
        assertThat(result.state).isEqualTo(DeliveryEvidenceState.UNAVAILABLE)
        assertThat(result.gates).isEmpty()
        assertThat(result.provenance).isNull()
        coVerify(exactly = 0) { github.profileArchive(any()) }
    }

    @Test
    fun `foreign PR wrong workflow branch SHA attempt and incomplete runs are rejected`(): Unit = runBlocking {
        val original = run.deepCopy()
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("event", "pull_request") },
            { it.put("path", ".github/workflows/untrusted.yml") },
            { it.put("head_sha", "b".repeat(40)) },
            { it.put("head_branch", "other") },
            { it.put("status", "in_progress") },
            { it.put("conclusion", "cancelled") },
            { (it.get("head_repository") as ObjectNode).put("id", 8) },
            { it.putArray("pull_requests").addObject().put("number", 1) },
        )
        for (mutate in mutations) {
            run = original.deepCopy().also(mutate)
            assertThat(adapter().collect().gates).isEmpty()
        }
        run = original.deepCopy()
        coEvery { github.profileResponse(attempt) } returns response(original.deepCopy().put("run_attempt", 1))
        assertThat(adapter().collect().gates).isEmpty()
    }

    @Test
    fun `artifact expiration freshness identity and digest bind the exact run and archive`(): Unit = runBlocking {
        val original = artifact.deepCopy()
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("expired", true) },
            { it.put("name", "gate-results-lint-a1") },
            { it.put("created_at", now.minusSeconds(86401).toString()) },
            { it.put("created_at", now.plusSeconds(1).toString()) },
            { it.put("expires_at", now.toString()) },
            { it.put("digest", "sha256:" + "0".repeat(64)) },
            { it.put("size_in_bytes", archive.size + 1) },
            { (it.get("workflow_run") as ObjectNode).put("id", 11) },
            { (it.get("workflow_run") as ObjectNode).put("repository_id", 8) },
            { (it.get("workflow_run") as ObjectNode).put("head_repository_id", 8) },
            { (it.get("workflow_run") as ObjectNode).put("head_sha", "b".repeat(40)) },
        )
        for (mutate in mutations) {
            artifact = original.deepCopy().also(mutate)
            val result = adapter().collect()
            assertThat(result.state).isEqualTo(DeliveryEvidenceState.UNAVAILABLE)
            assertThat(result.gates).isEmpty()
            assertThat(result.provenance).isNull()
        }
    }

    @Test
    fun `source changing while downloading cannot retain apparently verified observations`(): Unit = runBlocking {
        coEvery { github.profileResponse(ref) } returnsMany listOf(
            response("""{"ref":"refs/heads/main","object":{"type":"commit","sha":"$source"}}"""),
            response("""{"ref":"refs/heads/main","object":{"type":"commit","sha":"${"b".repeat(40)}"}}"""),
        )
        assertThat(adapter().collect().reason).isEqualTo("SOURCE_CHANGED_DURING_COLLECTION")
    }

    @Test
    fun `digest is verified before parsing even a syntactically valid JSON archive`(): Unit = runBlocking {
        publishArchive(zip(listOf("gate-results-lint.json" to "not JSON")))
        artifact.put("digest", "sha256:" + "0".repeat(64))
        assertThat(adapter().collect().reason).isEqualTo("ARTIFACT_DIGEST_MISMATCH")
    }

    @Test
    fun `unsafe oversized extra or missing archive entries and malformed gate schema are rejected`(): Unit =
        runBlocking {
            val cases = listOf(
                zip(listOf("../gate-results-lint.json" to "[$gate]")),
                zip(listOf("gate-results-other.json" to "[$gate]")),
                zip(listOf("gate-results-lint.json" to "[$gate]", "extra" to "extra")),
                zip(listOf("gate-results-lint.json" to " ".repeat(2 * 1024 * 1024 + 1))),
                zip(listOf("gate-results-lint.json" to "[$gate,$gate]")),
                zip(listOf("gate-results-lint.json" to "[${gate.replace("\"cpu_seconds\":5.0,", "")}]")),
                zip(
                    listOf(
                        "gate-results-lint.json" to "[${gate.replace("\"cpu_seconds\":5.0", "\"cpu_seconds\":-1")}]",
                    ),
                ),
                zip(
                    listOf(
                        "gate-results-lint.json" to "[${gate.replace("\"status\":\"ok\"", "\"status\":\"pending\"")}]",
                    ),
                ),
                zip(
                    listOf(
                        "gate-results-lint.json" to
                            "[${gate.replace("\"cpu_seconds\":5.0", "\"cpu_seconds\":5.0,\"cpu_seconds\":0")}]",
                    ),
                ),
                archive.copyOf(archive.size - 22), // Missing ZIP end-of-central-directory record.
                "not a zip".toByteArray(),
            )
            for (bytes in cases) {
                publishArchive(bytes)
                val result = adapter().collect()
                assertThat(result.state).isEqualTo(DeliveryEvidenceState.UNAVAILABLE)
                assertThat(result.gates).isEmpty()
            }
        }

    @Test
    fun `incomplete artifact list or missing exact-source run does not trigger a fallback search`(): Unit =
        runBlocking {
            coEvery { github.profileResponse(artifacts) } returns response("""{"total_count":101,"artifacts":[]}""")
            assertThat(adapter().collect().reason).isEqualTo("ARTIFACT_LIST_INCOMPLETE")
            coEvery { github.profileResponse(runs) } returns response("""{"total_count":0,"workflow_runs":[]}""")
            assertThat(adapter().collect().reason).isEqualTo("NO_EXACT_SOURCE_RUN")
        }

    @Test
    fun `an artifact host outside explicit egress policy remains unavailable`(): Unit = runBlocking {
        coEvery { github.profileArchive(location) } throws EgressDeniedException(
            EgressDecision.Denied(EgressDenialReason.HOST_NOT_ALLOWLISTED, "fixture denial"),
        )
        val result = adapter().collect()
        assertThat(result.state).isEqualTo(DeliveryEvidenceState.UNAVAILABLE)
        assertThat(result.provenance).isNull()
        assertThat(result.gates).isEmpty()
    }

    @Test
    fun `cancellation is propagated and transport exceptions become unavailable`() {
        coEvery { github.profileResponse("") } throws CancellationException("cancelled")
        assertThrows(CancellationException::class.java) { runBlocking { adapter().collect() } }
        coEvery { github.profileResponse("") } throws IllegalStateException("transport unavailable")
        assertThat(runBlocking { adapter().collect() }.state).isEqualTo(DeliveryEvidenceState.UNAVAILABLE)
    }
}
