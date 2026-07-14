// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.out.TemplatePublishConflictException
import com.openbank.document.application.port.out.TemplateRenderPort
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0162 version-resolution policy: publishing a template supersedes whatever is currently
 * PUBLISHED for the same `code` — that predecessor is retired in the same [TemplateRepositoryPort.publishReplacing]
 * call, atomically, never left coexisting with (or briefly absent alongside) its successor.
 */
class DocumentTemplateServiceTest {

    private val repo: TemplateRepositoryPort = mockk()
    private val renderPort: TemplateRenderPort = mockk()
    private val service = DocumentTemplateService(
        repo = repo,
        renderPort = renderPort,
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
    )

    @Test
    fun `publishing the first version of a code retires nothing`(): Unit = runBlocking {
        val draft = draftTemplate()
        coEvery { repo.findById(draft.id) } returns draft
        coEvery { repo.findLatestPublished(draft.code) } returns null
        var retireCallCount = 0
        var capturedToRetire: DocumentTemplate? = null
        coEvery { repo.publishReplacing(any(), any()) } coAnswers {
            retireCallCount++
            capturedToRetire = secondArg()
            firstArg()
        }

        val result = service.publishTemplate(draft.id)

        assertThat(result.status).isEqualTo(TemplateStatus.PUBLISHED)
        assertThat(retireCallCount).isEqualTo(1)
        assertThat(capturedToRetire).isNull()
    }

    @Test
    fun `publishing a new version retires the currently published sibling of the same code`(): Unit = runBlocking {
        val draft = draftTemplate(id = OTHER_ID, version = "1.1.0")
        val predecessor = draftTemplate(version = "1.0.0").publish()
        coEvery { repo.findById(draft.id) } returns draft
        coEvery { repo.findLatestPublished(draft.code) } returns predecessor
        var capturedToPublish: DocumentTemplate? = null
        var capturedToRetire: DocumentTemplate? = null
        coEvery { repo.publishReplacing(any(), any()) } coAnswers {
            capturedToPublish = firstArg()
            capturedToRetire = secondArg()
            firstArg()
        }

        service.publishTemplate(draft.id)

        assertThat(capturedToPublish?.id).isEqualTo(draft.id)
        assertThat(capturedToPublish?.status).isEqualTo(TemplateStatus.PUBLISHED)
        assertThat(capturedToRetire?.id).isEqualTo(predecessor.id)
        assertThat(capturedToRetire?.status).isEqualTo(TemplateStatus.RETIRED)
    }

    @Test
    fun `publishing does not try to retire itself when it is its own latest-published lookup result`(): Unit =
        runBlocking {
            // Defensive case: findLatestPublished(code) racing a concurrent publish could return the
            // row being published itself (e.g. re-entrant call) -- must never self-retire.
            val draft = draftTemplate()
            coEvery { repo.findById(draft.id) } returns draft
            coEvery { repo.findLatestPublished(draft.code) } returns draft
            var capturedToRetire: DocumentTemplate? = null
            coEvery { repo.publishReplacing(any(), any()) } coAnswers {
                capturedToRetire = secondArg()
                firstArg()
            }

            service.publishTemplate(draft.id)

            assertThat(capturedToRetire).isNull()
        }

    @Test
    fun `a concurrent publish race surfaces as a clear, catchable error`(): Unit = runBlocking {
        val draft = draftTemplate(id = OTHER_ID)
        coEvery { repo.findById(draft.id) } returns draft
        coEvery { repo.findLatestPublished(draft.code) } returns null
        coEvery { repo.publishReplacing(any(), any()) } throws TemplatePublishConflictException("lost the race")

        assertThatThrownBy { runBlocking { service.publishTemplate(draft.id) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("concurrently")
    }

    @Test
    fun `publishing an already-published template is rejected by the domain rule`(): Unit = runBlocking {
        val published = draftTemplate().publish()
        coEvery { repo.findById(published.id) } returns published

        assertThatThrownBy { runBlocking { service.publishTemplate(published.id) } }
            .isInstanceOf(IllegalArgumentException::class.java)

        coVerify(exactly = 0) { repo.publishReplacing(any(), any()) }
    }

    private fun draftTemplate(id: UUID = FIXED_ID, code: String = "VOP_CS", version: String = "1.0.0") =
        DocumentTemplate(
            id = id,
            code = code,
            version = version,
            name = "VOP",
            engine = TemplateEngine.HANDLEBARS,
            bodyHtml = "<html>{{name}}</html>",
            locale = "cs",
            status = TemplateStatus.DRAFT,
            productRef = null,
            classification = "restricted",
            createdAt = FIXED_NOW,
            createdBy = "system",
        )

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-01-15T10:15:30Z")
        val FIXED_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000030")
        val OTHER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000031")
    }
}
