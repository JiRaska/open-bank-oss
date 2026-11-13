// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.application.port.out.DeliveryEvidencePort
import com.openbank.devops.domain.model.DeliveryEvidence
import com.openbank.devops.domain.model.DeliveryEvidenceSource
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.domain.model.DeliveryPullRequest
import com.openbank.devops.domain.model.DeliveryWorkflowRun
import com.openbank.devops.infrastructure.config.DevOpsConfig
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

@ApplicationScoped
class GitHubDeliveryEvidenceAdapter(
    private val github: GitHubMetricsAdapter,
    private val mapper: ObjectMapper,
    private val config: DevOpsConfig,
) : DeliveryEvidencePort {
    override suspend fun collect(windowStart: Instant, windowEnd: Instant): DeliveryEvidence {
        val repository = "${config.githubOwner()}/${config.githubRepo()}"
        return withTimeoutOrNull(COLLECTION_TIMEOUT_MS) {
            val range = URLEncoder.encode("$windowStart..$windowEnd", StandardCharsets.UTF_8)
            val workflows = pages(
                path = { "/actions/runs?created=$range&per_page=$PAGE_SIZE&page=$it" },
                decode = { root ->
                    val rows = root.get("workflow_runs")
                    require(rows != null && rows.isArray)
                    val total = root.get("total_count")
                    require(
                        total != null &&
                            total.isIntegralNumber &&
                            total.canConvertToLong() &&
                            total.asLong() >= rows.size(),
                    )
                    Page(rows.map(::workflow), total.asLong(), rows.size() < PAGE_SIZE)
                },
                key = { it.id.toString() },
            )
            var previousUpdated: Instant? = null
            val pulls = pages(
                path = { "/pulls?state=closed&sort=updated&direction=desc&per_page=$PAGE_SIZE&page=$it" },
                decode = { root ->
                    require(root.isArray)
                    val rows = root.map(::pullRequest)
                    rows.forEach { row ->
                        require(previousUpdated?.let { !row.updatedAt.isAfter(it) } != false)
                        previousUpdated = row.updatedAt
                    }
                    Page(rows, null, rows.size < PAGE_SIZE || rows.any { it.updatedAt.isBefore(windowStart) })
                },
                key = { it.number.toString() },
            )
            DeliveryEvidence(repository, workflows, pulls)
        } ?: DeliveryEvidence(repository, unavailable("COLLECTION_TIMEOUT"), unavailable("COLLECTION_TIMEOUT"))
    }

    private data class Page<T>(val rows: List<T>, val total: Long?, val exhausted: Boolean)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> pages(
        path: (Int) -> String,
        decode: (JsonNode) -> Page<T>,
        key: (T) -> String,
    ): DeliveryEvidenceSource<T> {
        val records = linkedMapOf<String, T>()
        var pagesRead = 0
        var duplicate = false
        var expectedTotal: Long? = null
        fun result(state: DeliveryEvidenceState, reason: String? = null) =
            DeliveryEvidenceSource(state, records.values.toList(), pagesRead, reason)
        return try {
            for (pageNumber in 1..MAX_PAGES) {
                val body = github.deliveryPage(path(pageNumber)) ?: return result(
                    incompleteState(pagesRead > 0),
                    "SOURCE_UNAVAILABLE",
                )
                pagesRead++
                val page = decode(requireNotNull(mapper.readTree(body)))
                require(page.rows.size <= PAGE_SIZE)
                if (expectedTotal != null && page.total != expectedTotal) duplicate = true
                expectedTotal = page.total
                duplicate = appendPage(records, page.rows, key) || duplicate
                if (page.exhausted || page.total != null && records.size.toLong() >= page.total) {
                    return finalResult(duplicate, page.total, records.values, pagesRead)
                }
            }
            result(DeliveryEvidenceState.PARTIAL, "PAGE_LIMIT")
        } catch (ex: CancellationException) {
            throw ex
        } catch (_: Exception) {
            result(
                incompleteState(records.isNotEmpty()),
                "INVALID_RESPONSE",
            )
        }
    }

    private fun <T> appendPage(records: MutableMap<String, T>, rows: List<T>, key: (T) -> String): Boolean {
        var duplicate = false
        for (row in rows) {
            if (records.put(key(row), row) != null) duplicate = true
        }
        return duplicate
    }

    private fun <T> finalResult(
        duplicate: Boolean,
        total: Long?,
        records: Collection<T>,
        pagesRead: Int,
    ): DeliveryEvidenceSource<T> {
        val changed = duplicate || total != null && records.size.toLong() != total
        return DeliveryEvidenceSource(
            if (changed) DeliveryEvidenceState.PARTIAL else DeliveryEvidenceState.COMPLETE,
            records.toList(),
            pagesRead,
            if (changed) "PAGINATION_CHANGED" else null,
        )
    }

    private fun workflow(row: JsonNode): DeliveryWorkflowRun {
        val attempt = positiveInt(row, "run_attempt")
        return DeliveryWorkflowRun(
            number(row, "id"),
            text(row, "path"),
            text(row, "status"),
            nullableText(row, "conclusion"),
            Instant.parse(text(row, "created_at")),
            nullableText(row, "run_started_at")?.let(Instant::parse),
            attempt,
        )
    }

    private fun pullRequest(row: JsonNode): DeliveryPullRequest {
        val created = Instant.parse(text(row, "created_at"))
        val updated = Instant.parse(text(row, "updated_at"))
        val merged = nullableText(row, "merged_at")?.let(Instant::parse)
        require(!updated.isBefore(created) && (merged == null || !merged.isBefore(created) && !merged.isAfter(updated)))
        return DeliveryPullRequest(positiveInt(row, "number"), created, updated, merged)
    }

    private fun <T> unavailable(reason: String) =
        DeliveryEvidenceSource<T>(DeliveryEvidenceState.UNAVAILABLE, reason = reason)

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 5
        const val COLLECTION_TIMEOUT_MS = 15_000L
    }
}

private fun positiveInt(row: JsonNode, name: String): Int {
    val value = number(row, name)
    require(value <= Int.MAX_VALUE)
    return value.toInt()
}

private fun number(row: JsonNode, name: String): Long {
    val value = row.get(name)
    require(value != null && value.isIntegralNumber && value.canConvertToLong() && value.asLong() > 0)
    return value.asLong()
}

private fun text(row: JsonNode, name: String): String {
    val value = row.get(name)
    require(value != null && value.isTextual && value.asText().isNotBlank())
    return value.asText()
}

private fun nullableText(row: JsonNode, name: String): String? {
    val value = row.get(name)
    require(value != null)
    return if (value.isNull) null else text(row, name)
}

private fun incompleteState(observed: Boolean) =
    if (observed) DeliveryEvidenceState.PARTIAL else DeliveryEvidenceState.UNAVAILABLE
