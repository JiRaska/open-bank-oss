// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.infrastructure.config.DevOpsConfig
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
import java.time.Instant

class GitHubDeliveryEvidenceAdapterTest {
    private val github = mockk<GitHubMetricsAdapter>()
    private val config = mockk<DevOpsConfig>()
    private val mapper = ObjectMapper()
    private val adapter = GitHubDeliveryEvidenceAdapter(github, mapper, config)
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val end = Instant.parse("2026-01-02T00:00:00Z")

    @BeforeEach
    fun setup() {
        every { config.githubOwner() } returns "example"
        every { config.githubRepo() } returns "repo"
        coEvery { github.deliveryPage(any()) } returns null
        coEvery { github.deliveryPage(match { it.startsWith("/pulls?") }) } returns "[]"
    }

    private fun workflow(id: Int = 1): String = """{"id":$id,"run_attempt":1,"path":".github/workflows/ci.yml",
        "status":"completed","conclusion":"success","created_at":"$start","run_started_at":"$start"}"""

    @Test
    fun `complete empty and unavailable are different evidence states`(): Unit = runBlocking {
        assertThat(adapter.collect(start, end).workflows.state).isEqualTo(DeliveryEvidenceState.UNAVAILABLE)
        coEvery { github.deliveryPage(match { it.startsWith("/actions/") }) } returns
            """{"total_count":0,"workflow_runs":[]}"""
        val result = adapter.collect(start, end)
        assertThat(result.workflows.state).isEqualTo(DeliveryEvidenceState.COMPLETE)
        assertThat(result.workflows.records).isEmpty()
        assertThat(result.repository).isEqualTo("example/repo")
    }

    @Test
    fun `page cap never masquerades as a complete cohort`(): Unit = runBlocking {
        val rows = (1..100).joinToString(",") { workflow(it) }
        coEvery { github.deliveryPage(match { it.startsWith("/actions/") }) } returns
            """{"total_count":999,"workflow_runs":[$rows]}"""
        val result = adapter.collect(start, end).workflows
        assertThat(result.state).isEqualTo(DeliveryEvidenceState.PARTIAL)
        assertThat(result.reason).isEqualTo("PAGE_LIMIT")
        assertThat(result.records).hasSize(100)
        assertThat(result.pagesRead).isEqualTo(5)
        coVerify(exactly = 5) { github.deliveryPage(match { it.startsWith("/actions/") }) }
    }

    @Test
    fun `duplicates are deduplicated and mark pagination drift`(): Unit = runBlocking {
        val rows = (1..100).joinToString(",") { workflow(it) }
        coEvery { github.deliveryPage(match { it.startsWith("/actions/") }) } returnsMany listOf(
            """{"total_count":101,"workflow_runs":[$rows]}""",
            """{"total_count":101,"workflow_runs":[${workflow(100)}]}""",
        )
        val result = adapter.collect(start, end).workflows
        assertThat(result.state).isEqualTo(DeliveryEvidenceState.PARTIAL)
        assertThat(result.reason).isEqualTo("PAGINATION_CHANGED")
        assertThat(result.records).hasSize(100)
    }

    @Test
    fun `a changed total marks even distinct complete pages as partial`(): Unit = runBlocking {
        val rows = (1..100).joinToString(",") { workflow(it) }
        coEvery { github.deliveryPage(match { it.startsWith("/actions/") }) } returnsMany listOf(
            """{"total_count":102,"workflow_runs":[$rows]}""",
            """{"total_count":101,"workflow_runs":[${workflow(101)}]}""",
        )
        val result = adapter.collect(start, end).workflows
        assertThat(result.state).isEqualTo(DeliveryEvidenceState.PARTIAL)
        assertThat(result.reason).isEqualTo("PAGINATION_CHANGED")
        assertThat(result.records).hasSize(101)
    }

    @Test
    fun `a failed later page preserves only explicitly partial observations`(): Unit = runBlocking {
        val rows = (1..100).joinToString(",") { workflow(it) }
        coEvery { github.deliveryPage(match { it.startsWith("/actions/") }) } returnsMany listOf(
            """{"total_count":101,"workflow_runs":[$rows]}""",
            null,
        )
        val result = adapter.collect(start, end).workflows
        assertThat(result.state).isEqualTo(DeliveryEvidenceState.PARTIAL)
        assertThat(result.reason).isEqualTo("SOURCE_UNAVAILABLE")
        assertThat(result.records).hasSize(100)
    }

    @Test
    fun `malformed counts identifiers timestamps and rows cannot produce a healthy zero`(): Unit = runBlocking {
        val bad = listOf(
            "{}",
            "not json",
            "null",
            """{"total_count":-1,"workflow_runs":[]}""",
            """{"total_count":18446744073709551616,"workflow_runs":[]}""",
            """{"total_count":1,"workflow_runs":[${workflow().replace(
                "\"run_attempt\":1",
                "\"run_attempt\":4294967297",
            )}]}""",
            """{"total_count":1,"workflow_runs":[${workflow().replace(start.toString(), "bad-time")}]}""",
        )
        for (body in bad) {
            coEvery { github.deliveryPage(match { it.startsWith("/actions/") }) } returns body
            assertThat(adapter.collect(start, end).workflows.state).describedAs(body)
                .isEqualTo(DeliveryEvidenceState.UNAVAILABLE)
        }
    }

    @Test
    fun `closed PR pagination stops at an older updated row without treating closure as merge`(): Unit = runBlocking {
        coEvery { github.deliveryPage(match { it.startsWith("/pulls?") }) } returns
            """[{"number":1,"created_at":"2025-12-30T00:00:00Z","updated_at":"2025-12-31T00:00:00Z","merged_at":null}]"""
        val result = adapter.collect(start, end).pullRequests
        assertThat(result.state).isEqualTo(DeliveryEvidenceState.COMPLETE)
        assertThat(result.records.single().mergedAt).isNull()
        coVerify(exactly = 1) { github.deliveryPage(match { it.startsWith("/pulls?") }) }
    }

    @Test
    fun `cancellation is propagated rather than classified as evidence`() {
        coEvery { github.deliveryPage(any()) } throws CancellationException("cancelled")
        assertThrows(CancellationException::class.java) { runBlocking { adapter.collect(start, end) } }
    }
}
