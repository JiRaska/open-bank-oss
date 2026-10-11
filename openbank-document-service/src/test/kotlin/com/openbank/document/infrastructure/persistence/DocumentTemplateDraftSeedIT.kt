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
