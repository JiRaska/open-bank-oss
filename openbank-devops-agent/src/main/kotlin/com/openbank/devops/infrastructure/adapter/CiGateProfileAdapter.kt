// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.application.port.out.CiGateProfilePort
import com.openbank.devops.domain.model.CiGateObservation
import com.openbank.devops.domain.model.CiGateProfileEvidence
import com.openbank.devops.domain.model.CiGateProfileProvenance
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.infrastructure.config.DevOpsConfig
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** One current-source artifact, not a whole-pipeline ranking or a claim of savings. */
@ApplicationScoped
@Suppress("TooManyFunctions") // Small field validators keep malformed evidence fail-closed.
class CiGateProfileAdapter(
    private val github: GitHubMetricsAdapter,
    mapper: ObjectMapper,
    private val config: DevOpsConfig,
    private val clock: Clock,
) : CiGateProfilePort {
    private val json = mapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun collect(): CiGateProfileEvidence {
        val repository = "${config.githubOwner()}/${config.githubRepo()}"
        val now = Instant.now(clock)
        fun unavailable(reason: String) = CiGateProfileEvidence(
            repository,
            now,
            DeliveryEvidenceState.UNAVAILABLE,
            reason,
        )
        return try {
            withTimeoutOrNull(COLLECTION_TIMEOUT_MS) { collect(repository, now) }
                ?: unavailable("COLLECTION_TIMEOUT")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: ProfileRejected) {
            unavailable(rejected.reason)
        } catch (_: Exception) {
            unavailable("SOURCE_UNAVAILABLE_OR_INVALID")
        }
    }

    private suspend fun collect(repository: String, now: Instant): CiGateProfileEvidence {
        val repo = metadata("")
        ensure(text(repo, "full_name").equals(repository, ignoreCase = true), "REPOSITORY_MISMATCH")
        val repositoryId = positive(repo, "id")
        val branch = text(repo, "default_branch")
        val source = head(branch)
        val runs = metadata("/actions/workflows/ci.yml/runs?event=push&status=completed&head_sha=$source&per_page=1")
        val runRows = array(runs, "workflow_runs")
        ensure(runRows.size() == 1 && nonnegative(runs, "total_count") >= 1, "NO_EXACT_SOURCE_RUN")
        val listedRun = runRows[0]
        trustedRun(listedRun, repository, repositoryId, branch, source, now)
        val runId = positive(listedRun, "id")
        val attempt = positiveInt(listedRun, "run_attempt")
        val run = metadata("/actions/runs/$runId/attempts/$attempt")
        trustedRun(run, repository, repositoryId, branch, source, now)
        ensure(positive(run, "id") == runId && positiveInt(run, "run_attempt") == attempt, "RUN_ATTEMPT_MISMATCH")
        val artifact = artifact(runId, attempt)
        val provenance = provenance(artifact, runId, attempt, repositoryId, branch, source, now)
        val archive = download(provenance.artifactId)
        ensure(archive.size.toLong() == positive(artifact, "size_in_bytes"), "ARTIFACT_SIZE_MISMATCH")
        ensure("sha256:${sha256(archive)}" == provenance.artifactDigest, "ARTIFACT_DIGEST_MISMATCH")
        val gates = gates(archive, provenance.artifactName)
        ensure(head(branch) == source, "SOURCE_CHANGED_DURING_COLLECTION")
        return CiGateProfileEvidence(
            repository,
            now,
            DeliveryEvidenceState.PARTIAL,
            "SINGLE_ARTIFACT",
            provenance,
            gates,
        )
    }

    private suspend fun metadata(path: String): JsonNode {
        val response = github.profileResponse(path) ?: throw ProfileRejected("SOURCE_UNAVAILABLE")
        ensure(response.status == HTTP_OK && response.body.size <= MAX_METADATA_BYTES, "METADATA_UNAVAILABLE_OR_LARGE")
        return json.readTree(response.body).also { ensure(it != null && it.isObject, "INVALID_METADATA") }
    }

    private suspend fun head(branch: String): String {
        val encoded = URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20")
        val ref = metadata("/git/ref/heads/$encoded")
        ensure(text(ref, "ref") == "refs/heads/$branch", "DEFAULT_BRANCH_REF_MISMATCH")
        val obj = ref.get("object") ?: throw ProfileRejected("INVALID_REF")
        ensure(text(obj, "type") == "commit", "INVALID_REF")
        return text(obj, "sha").also { ensure(SHA.matches(it), "INVALID_SOURCE_SHA") }
    }

    private fun trustedRun(
        run: JsonNode,
        repository: String,
        repositoryId: Long,
        branch: String,
        source: String,
        now: Instant,
    ) {
        ensure(text(run, "path") == CI_PATH && text(run, "event") == "push", "UNTRUSTED_WORKFLOW")
        ensure(text(run, "status") == "completed", "RUN_INCOMPLETE")
        ensure(nullableText(run, "conclusion") in setOf("success", "failure"), "RUN_CONCLUSION_UNSUPPORTED")
        ensure(text(run, "head_branch") == branch && text(run, "head_sha") == source, "RUN_SOURCE_MISMATCH")
        ensure(array(run, "pull_requests").isEmpty, "PULL_REQUEST_RUN")
        for (name in listOf("repository", "head_repository")) {
            val item = run.get(name) ?: throw ProfileRejected("RUN_REPOSITORY_MISSING")
            ensure(positive(item, "id") == repositoryId, "RUN_REPOSITORY_MISMATCH")
            ensure(text(item, "full_name").equals(repository, ignoreCase = true), "RUN_REPOSITORY_MISMATCH")
        }
        fresh(Instant.parse(text(run, "updated_at")), now)
    }

    private suspend fun artifact(runId: Long, attempt: Int): JsonNode {
        val response = metadata("/actions/runs/$runId/artifacts?per_page=100")
        val rows = array(response, "artifacts")
        ensure(
            rows.size() <= MAX_ARTIFACTS && nonnegative(response, "total_count") == rows.size().toLong(),
            "ARTIFACT_LIST_INCOMPLETE",
        )
        val candidates = rows.filter {
            ARTIFACT.matchEntire(text(it, "name"))?.groupValues?.get(2) == attempt.toString()
        }
        ensure(candidates.isNotEmpty(), "NO_ATTEMPT_ARTIFACT")
        ensure(rows.map { positive(it, "id") }.distinct().size == rows.size(), "DUPLICATE_ARTIFACT")
        ensure(candidates.map { text(it, "name") }.distinct().size == candidates.size, "DUPLICATE_ARTIFACT")
        return candidates.minBy { text(it, "name") }
    }

    @Suppress("LongParameterList")
    private fun provenance(
        artifact: JsonNode,
        runId: Long,
        attempt: Int,
        repositoryId: Long,
        branch: String,
        source: String,
        now: Instant,
    ): CiGateProfileProvenance {
        ensure(!boolean(artifact, "expired"), "ARTIFACT_EXPIRED")
        val size = positive(artifact, "size_in_bytes")
        ensure(size <= MAX_ARCHIVE_BYTES, "ARTIFACT_TOO_LARGE")
        val created = Instant.parse(text(artifact, "created_at"))
        fresh(created, now)
        ensure(Instant.parse(text(artifact, "expires_at")).isAfter(now), "ARTIFACT_EXPIRED")
        val workflow = artifact.get("workflow_run") ?: throw ProfileRejected("ARTIFACT_RUN_MISSING")
        ensure(positive(workflow, "id") == runId, "ARTIFACT_RUN_MISMATCH")
        ensure(positive(workflow, "repository_id") == repositoryId, "ARTIFACT_REPOSITORY_MISMATCH")
        ensure(positive(workflow, "head_repository_id") == repositoryId, "ARTIFACT_REPOSITORY_MISMATCH")
        ensure(
            text(workflow, "head_sha") == source && text(workflow, "head_branch") == branch,
            "ARTIFACT_SOURCE_MISMATCH",
        )
        val digest = text(artifact, "digest")
        ensure(DIGEST.matches(digest), "ARTIFACT_DIGEST_MISSING")
        return CiGateProfileProvenance(
            source,
            runId,
            attempt,
            positive(artifact, "id"),
            text(artifact, "name"),
            digest,
            created,
        )
    }

    private suspend fun download(artifactId: Long): ByteArray {
        val response = present(github.profileResponse("/actions/artifacts/$artifactId/zip"), "ARTIFACT_UNAVAILABLE")
        ensure(response.status == HTTP_REDIRECT, "ARTIFACT_REDIRECT_REQUIRED")
        val location = present(response.header("location"), "ARTIFACT_LOCATION_MISSING")
        val archive = present(github.profileArchive(location), "ARTIFACT_UNAVAILABLE")
        ensure(archive.status == HTTP_OK && archive.body.size <= MAX_ARCHIVE_BYTES, "ARTIFACT_UNAVAILABLE_OR_LARGE")
        return archive.body
    }

    private fun gates(archive: ByteArray, name: String): List<CiGateObservation> {
        val slug = ARTIFACT.matchEntire(name)?.groupValues?.get(1) ?: throw ProfileRejected("ARTIFACT_NAME_INVALID")
        val bytes = archiveJson(archive, "gate-results-$slug.json")
        val rows = json.readTree(bytes)
        ensure(rows != null && rows.isArray && rows.size() in 1..MAX_GATES, "GATE_RECORDS_INVALID")
        val result = rows.map(::gate)
        ensure(result.map { it.id }.distinct().size == result.size, "DUPLICATE_GATE")
        return result
    }

    private fun archiveJson(archive: ByteArray, expectedName: String): ByteArray {
        val temporary = Files.createTempFile("ci-gate-profile-", ".zip")
        return try {
            Files.write(temporary, archive)
            // ZipFile verifies the central directory; streaming local headers alone accepts truncation.
            ZipFile(temporary.toFile()).use { zip ->
                val entries = zip.entries()
                ensure(entries.hasMoreElements(), "ARTIFACT_EMPTY")
                val entry = entries.nextElement()
                ensure(!entries.hasMoreElements(), "ARTIFACT_EXTRA_ENTRIES")
                ensure(!entry.isDirectory && entry.name == expectedName, "ARTIFACT_ENTRY_INVALID")
                ensure(entry.size in 0..MAX_JSON_BYTES.toLong(), "ARTIFACT_CONTENT_INVALID")
                ensure(entry.compressedSize in 0..MAX_ARCHIVE_BYTES, "ARTIFACT_CONTENT_INVALID")
                val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_JSON_BYTES + 1) }
                ensure(bytes.size <= MAX_JSON_BYTES && bytes.size.toLong() == entry.size, "ARTIFACT_CONTENT_INVALID")
                ensure(CRC32().apply { update(bytes) }.value == entry.crc, "ARTIFACT_CRC_INVALID")
                bytes
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun gate(row: JsonNode): CiGateObservation {
        ensure(row.isObject, "GATE_RECORD_INVALID")
        val status = text(row, "status")
        val mode = text(row, "mode")
        val failure = nullableText(row, "failure_kind")
        ensure(
            status in STATUSES && mode in MODES && (failure == null || failure in FAILURE_KINDS),
            "GATE_VERDICT_INVALID",
        )
        ensure((status in setOf("ok", "skipped")) == (failure == null), "GATE_FAILURE_KIND_INVALID")
        return CiGateObservation(
            text(row, "id"), nullableText(row, "group"), mode, status, failure,
            number(row, "seconds"), number(row, "cpu_seconds"), nullableNumber(row, "budget_seconds"),
            number(row, "selftest_seconds"), boolean(row, "selftest_declared"), nullableBoolean(row, "selftest_passed"),
            boolean(row, "selftest_skipped"), nullableInt(row, "subjects"), nullableInt(row, "min_subjects"),
            boolean(row, "wall_over_budget"),
        )
    }

    private fun fresh(at: Instant, now: Instant) {
        ensure(!at.isAfter(now) && !at.isBefore(now.minus(MAX_AGE)), "EVIDENCE_STALE_OR_FUTURE")
    }

    private fun text(row: JsonNode, name: String): String {
        val value = row.get(name)
        ensure(
            value != null && value.isTextual && value.asText().isNotBlank() && value.asText().length <= MAX_TEXT,
            "INVALID_TEXT",
        )
        return value.asText()
    }

    private fun nullableText(row: JsonNode, name: String): String? =
        if (row.get(name) == null || row.get(name).isNull) null else text(row, name)

    private fun array(row: JsonNode, name: String): JsonNode =
        (row.get(name) ?: throw ProfileRejected("MISSING_ARRAY")).also { ensure(it.isArray, "INVALID_ARRAY") }

    private fun nonnegative(row: JsonNode, name: String): Long {
        val value = row.get(name)
        ensure(
            value != null && value.isIntegralNumber && value.canConvertToLong() && value.asLong() >= 0,
            "INVALID_INTEGER",
        )
        return value.asLong()
    }

    private fun positive(row: JsonNode, name: String): Long = nonnegative(row, name).also {
        ensure(it > 0, "INVALID_IDENTIFIER")
    }

    private fun positiveInt(row: JsonNode, name: String): Int = positive(row, name).also {
        ensure(it <= Int.MAX_VALUE, "INVALID_IDENTIFIER")
    }.toInt()

    private fun nullableInt(row: JsonNode, name: String): Int? {
        if (row.get(name) == null || row.get(name).isNull) return null
        return nonnegative(row, name).also { ensure(it <= Int.MAX_VALUE, "INVALID_INTEGER") }.toInt()
    }

    private fun number(row: JsonNode, name: String): Double {
        val value = row.get(name)
        ensure(value != null && value.isNumber, "INVALID_COST")
        return value.asDouble().also { ensure(it.isFinite() && it >= 0, "INVALID_COST") }
    }

    private fun nullableNumber(row: JsonNode, name: String): Double? =
        if (row.get(name) == null || row.get(name).isNull) null else number(row, name)

    private fun boolean(row: JsonNode, name: String): Boolean {
        val value = row.get(name)
        ensure(value != null && value.isBoolean, "INVALID_BOOLEAN")
        return value.asBoolean()
    }

    private fun nullableBoolean(row: JsonNode, name: String): Boolean? =
        if (row.get(name) == null || row.get(name).isNull) null else boolean(row, name)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T : Any> present(value: T?, reason: String): T = value ?: throw ProfileRejected(reason)

    private fun ensure(condition: Boolean, reason: String) {
        if (!condition) throw ProfileRejected(reason)
    }

    private class ProfileRejected(val reason: String) : RuntimeException(reason)

    private companion object {
        const val COLLECTION_TIMEOUT_MS = 20_000L
        const val MAX_METADATA_BYTES = 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_ARTIFACTS = 100
        const val MAX_GATES = 2000
        const val MAX_TEXT = 512
        const val HTTP_OK = 200
        const val HTTP_REDIRECT = 302
        const val CI_PATH = ".github/workflows/ci.yml"
        val MAX_AGE: Duration = Duration.ofHours(24)
        val SHA = Regex("[0-9a-f]{40}")
        val DIGEST = Regex("sha256:[0-9a-f]{64}")
        val ARTIFACT = Regex("gate-results-([a-z0-9_-]+)-a([1-9][0-9]*)")
        val STATUSES = setOf("ok", "failed", "warned", "skipped", "unfalsified")
        val MODES = setOf("enforced", "advisory")
        val FAILURE_KINDS = setOf("content", "budget", "not_evaluated", "timeout", "unfalsified", "setup")
    }
}
