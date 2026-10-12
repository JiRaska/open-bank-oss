// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence

import com.openbank.document.domain.DocumentTemplateSeed
import com.openbank.document.domain.model.TemplateStatus
import com.openbank.document.infrastructure.persistence.entity.DocumentTemplateEntity
import com.openbank.document.infrastructure.persistence.repository.DocumentTemplateRepositoryImpl
import com.openbank.document.it.PostgresRedisTestResource
import io.quarkus.runtime.StartupEvent
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class DocumentTemplateDraftSeedIT {
    @Inject
    lateinit var repo: DocumentTemplateRepositoryImpl

    @Inject
    lateinit var sf: Mutiny.SessionFactory

    @Inject
    lateinit var seeder: DocumentTemplateSeeder

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private suspend fun applyMigration(sql: String) {
        sf.withTransaction { session ->
            session.createNativeQuery<Any>(sql).executeUpdate()
        }.awaitSuspending()
    }

    @Test
    fun `migration fingerprints match the eight current seed bodies`() {
        val migration = requireNotNull(
            javaClass.classLoader.getResource("db/migration/V15__retire_unreviewed_pension_seed_publications.sql"),
        ).readText()
        val pattern = Regex("'([0-9a-f-]+)'::uuid, '([^']+)', '([0-9a-f]{32})'")
        val fingerprints = pattern.findAll(migration).associate { match ->
            match.groupValues[2] to (match.groupValues[1] to match.groupValues[3])
        }
        val actual = DocumentTemplateSeed.templates.filter { it.code.startsWith("pension-") }.associate { template ->
            val digest = MessageDigest.getInstance("MD5").digest(template.bodyHtml.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            template.code to (template.id.toString() to digest)
        }
        assertThat(fingerprints).hasSize(8).isEqualTo(actual)
    }

    @Test
    fun `migration demotes all eight original publications but leaves changed revisions untouched`() {
        val drafts = DocumentTemplateSeed.templates.filter { it.code.startsWith("pension-") }
        val changedDraft = drafts.first()
        val migration = requireNotNull(
            javaClass.classLoader.getResource("db/migration/V15__retire_unreviewed_pension_seed_publications.sql"),
        ).readText()

        try {
            onContext {
                for (draft in drafts) {
                    sf.withTransaction { session ->
                        session.find(DocumentTemplateEntity::class.java, draft.id).invoke { row ->
                            row.status = TemplateStatus.PUBLISHED
                        }.replaceWithVoid()
                    }.awaitSuspending()
                }
                applyMigration(migration)
                for (draft in drafts) {
                    assertThat(repo.findById(draft.id)?.status).isEqualTo(TemplateStatus.DRAFT)
                }

                // A changed body at the same fixed ID is not proven to be the original seed.
                sf.withTransaction { session ->
                    session.find(DocumentTemplateEntity::class.java, changedDraft.id).invoke { row ->
                        row.bodyHtml += "<!-- changed after seeding -->"
                        row.status = TemplateStatus.PUBLISHED
                    }.replaceWithVoid()
                }.awaitSuspending()
                applyMigration(migration)
                assertThat(repo.findById(changedDraft.id)?.status).isEqualTo(TemplateStatus.PUBLISHED)

                sf.withTransaction { session ->
                    session.find(DocumentTemplateEntity::class.java, changedDraft.id).invoke { row ->
                        row.bodyHtml = changedDraft.bodyHtml
                        row.createdBy = "reviewer"
                    }.replaceWithVoid()
                }.awaitSuspending()
                applyMigration(migration)
                assertThat(repo.findById(changedDraft.id)?.status).isEqualTo(TemplateStatus.PUBLISHED)

                sf.withTransaction { session ->
                    session.find(DocumentTemplateEntity::class.java, changedDraft.id).invoke { row ->
                        row.createdBy = changedDraft.createdBy
                        row.version = "1.0.1"
                    }.replaceWithVoid()
                }.awaitSuspending()
                applyMigration(migration)
                assertThat(repo.findById(changedDraft.id)?.status).isEqualTo(TemplateStatus.PUBLISHED)
            }
        } finally {
            onContext {
                for (draft in drafts) {
                    sf.withTransaction { session ->
                        session.find(DocumentTemplateEntity::class.java, draft.id).invoke { row ->
                            row.bodyHtml = draft.bodyHtml
                            row.status = TemplateStatus.DRAFT
                            row.createdBy = draft.createdBy
                            row.version = draft.version
                        }.replaceWithVoid()
                    }.awaitSuspending()
                }
            }
        }
    }

    @Test
    fun `seeding a pension draft preserves the existing published version`() {
        val draft = DocumentTemplateSeed.templates.first { it.code == "pension-dps-key-information-cs" }
        val published = draft.copy(id = UUID.randomUUID(), version = "0.9.0", status = TemplateStatus.PUBLISHED)
        onContext {
            sf.withTransaction { session ->
                session.find(DocumentTemplateEntity::class.java, draft.id).flatMap { session.remove(it) }
            }.awaitSuspending()
            repo.save(published)
        }
        try {
            seeder.onStart(StartupEvent())
            onContext {
                assertThat(repo.findById(draft.id)?.status).isEqualTo(TemplateStatus.DRAFT)
                assertThat(repo.findLatestPublished(draft.code)?.id).isEqualTo(published.id)
            }
        } finally {
            onContext {
                sf.withTransaction { session ->
                    session.find(DocumentTemplateEntity::class.java, published.id).flatMap { session.remove(it) }
                }.awaitSuspending()
            }
        }
    }
}
