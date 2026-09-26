// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.repository

import com.openbank.document.application.port.out.TemplatePublishConflictException
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import com.openbank.document.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Real-Postgres coverage for the ADR-0162 version-resolution policy: [findLatestPublished] and the
 * atomicity of [publishReplacing] — including the partial unique index
 * (`uq_document_templates_one_published_per_code`, V5 migration) that makes "two PUBLISHED rows
 * for one code" a hard DB error rather than a silent possibility, mirroring the exact defect found
 * live in the seed data (two coexisting PUBLISHED versions of the same VOP/framework/account codes).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class DocumentTemplateRepositoryImplIT {

    @Inject
    lateinit var repo: DocumentTemplateRepositoryImpl

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun draft(id: UUID, code: String, version: String, createdAt: Instant) = DocumentTemplate(
        id = id,
        code = code,
        version = version,
        name = "Template",
        engine = TemplateEngine.HANDLEBARS,
        bodyHtml = "<html>body</html>",
        locale = "en",
        status = TemplateStatus.DRAFT,
        productRef = null,
        classification = "restricted",
        createdAt = createdAt,
        createdBy = "test",
    )

    @Test
    fun `findLatestPublished returns null when nothing is published for the code`(): Unit = onVertxContext {
        assertThat(repo.findLatestPublished("NO_SUCH_CODE_${UUID.randomUUID()}")).isNull()
    }

    @Test
    fun `findLatestPublished returns the most recently created published row for a code`(): Unit = onVertxContext {
        val code = "IT_CODE_${UUID.randomUUID()}"
        val older = repo.save(draft(UUID.randomUUID(), code, "1.0.0", Instant.parse("2026-01-01T00:00:00Z")))
        val newer = repo.save(draft(UUID.randomUUID(), code, "1.1.0", Instant.parse("2026-02-01T00:00:00Z")))
        repo.publishReplacing(older.publish(), null)
        repo.publishReplacing(newer.publish(), older.publish().retire())

        val latest = repo.findLatestPublished(code)

        assertThat(latest?.id).isEqualTo(newer.id)
        assertThat(latest?.version).isEqualTo("1.1.0")
    }

    @Test
    fun `publishReplacing publishes the new version and retires the old one atomically`(): Unit = onVertxContext {
        val code = "IT_CODE_${UUID.randomUUID()}"
        val v1 = repo.save(draft(UUID.randomUUID(), code, "1.0.0", Instant.parse("2026-01-01T00:00:00Z")))
        val v2 = repo.save(draft(UUID.randomUUID(), code, "1.1.0", Instant.parse("2026-02-01T00:00:00Z")))
        repo.publishReplacing(v1.publish(), null)

        repo.publishReplacing(v2.publish(), v1.publish().retire())

        assertThat(repo.findById(v2.id)?.status).isEqualTo(TemplateStatus.PUBLISHED)
        assertThat(repo.findById(v1.id)?.status).isEqualTo(TemplateStatus.RETIRED)
        assertThat(repo.findLatestPublished(code)?.id).isEqualTo(v2.id)
    }

    @Test
    fun `publishing a second version of the same code without retiring the first is rejected`(): Unit = onVertxContext {
        // Exercises the DB-level backstop directly (bypassing the use-case's own retire logic) --
        // the partial unique index must reject this, not silently leave two PUBLISHED rows.
        val code = "IT_CODE_${UUID.randomUUID()}"
        val v1 = repo.save(draft(UUID.randomUUID(), code, "1.0.0", Instant.parse("2026-01-01T00:00:00Z")))
        val v2 = repo.save(draft(UUID.randomUUID(), code, "1.1.0", Instant.parse("2026-02-01T00:00:00Z")))
        repo.publishReplacing(v1.publish(), null)

        val rejected = try {
            repo.publishReplacing(v2.publish(), null)
            null
        } catch (e: TemplatePublishConflictException) {
            e
        }

        assertThat(rejected).isNotNull
        assertThat(repo.findById(v1.id)?.status).isEqualTo(TemplateStatus.PUBLISHED)
        assertThat(repo.findById(v2.id)?.status).isEqualTo(TemplateStatus.DRAFT)
    }

    @Test
    fun `publishReplacing with no predecessor just publishes`(): Unit = onVertxContext {
        val code = "IT_CODE_${UUID.randomUUID()}"
        val v1 = repo.save(draft(UUID.randomUUID(), code, "1.0.0", Instant.parse("2026-01-01T00:00:00Z")))

        repo.publishReplacing(v1.publish(), null)

        assertThat(repo.findById(v1.id)?.status).isEqualTo(TemplateStatus.PUBLISHED)
    }
}
