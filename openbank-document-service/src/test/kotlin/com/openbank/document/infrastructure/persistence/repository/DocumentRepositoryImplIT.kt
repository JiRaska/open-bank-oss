// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.repository

import com.openbank.document.application.port.out.DuplicateDocumentException
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.it.PostgresRedisTestResource
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
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
 * Data-level idempotency for onboarding-document issuance (ADR-0162 D7): the partial unique index on
 * `documents.idempotency_key` (V6) is the backstop that turns a concurrent/at-least-once double
 * delivery into a caught [DuplicateDocumentException] instead of a duplicate contract — behaviour a
 * mocked repository cannot exercise, so this needs a real Postgres (Testcontainers).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class DocumentRepositoryImplIT {

    @Inject
    lateinit var repo: DocumentRepositoryImpl

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun onboardingDoc(idempotencyKey: String, id: UUID = Ids.newId()) = Document(
        id = id,
        templateCode = "UCET_SMLOUVA_CS",
        templateVersion = "1.0.0",
        sha256 = "a".repeat(64),
        storageKey = "documents/$id",
        contentType = "application/pdf",
        sizeBytes = 10,
        status = DocumentStatus.GENERATED,
        metadata = emptyMap(),
        partyRef = "party-1",
        caseRef = "acc-1",
        productRef = "prod-1",
        retainUntil = null,
        createdAt = Instant.now(),
        idempotencyKey = idempotencyKey,
    )

    private fun outbox(aggregateId: UUID) =
        OutboxMessage(aggregateId = aggregateId, eventType = "document.generated.v1", payload = "{}")

    @Test
    fun `findByIdempotencyKey returns the document persisted under that key`(): Unit = onVertxContext {
        val doc = onboardingDoc("onboarding:${UUID.randomUUID()}")
        repo.saveWithOutbox(doc, outbox(doc.id))

        val found = repo.findByIdempotencyKey(doc.idempotencyKey!!)

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(doc.id)
        assertThat(repo.findByIdempotencyKey("onboarding:${UUID.randomUUID()}")).isNull()
    }

    @Test
    fun `a second document with the same idempotency key is rejected, not duplicated`(): Unit = onVertxContext {
        val key = "onboarding:${UUID.randomUUID()}"
        repo.saveWithOutbox(onboardingDoc(key), outbox(UUID.randomUUID()))

        // A different document id, same idempotency key (a replayed/concurrent onboarding delivery).
        val duplicate = onboardingDoc(key)
        val rejected = try {
            repo.saveWithOutbox(duplicate, outbox(duplicate.id))
            null
        } catch (e: DuplicateDocumentException) {
            e
        }

        assertThat(rejected).isNotNull
        // The original row is the only one under that key.
        assertThat(repo.findByIdempotencyKey(key)!!.id).isNotEqualTo(duplicate.id)
    }

    @Test
    fun `save updates a document that already exists instead of inserting it again`(): Unit = onVertxContext {
        // The onboarding language switch archives the stale agreement via save() (ADR-0169 D3).
        // archive() keeps the id, so this is an UPDATE — a plain persist() would hit
        // documents_pkey and surface as a 500. OnboardingDocumentServiceTest mocks the repository,
        // so only a real Postgres catches it.
        val doc = onboardingDoc("onboarding:${UUID.randomUUID()}")
        repo.saveWithOutbox(doc, outbox(doc.id))

        val archived = repo.save(doc.copy(status = DocumentStatus.ARCHIVED))

        assertThat(archived.id).isEqualTo(doc.id)
        assertThat(repo.findById(doc.id)!!.status).isEqualTo(DocumentStatus.ARCHIVED)
    }

    @Test
    fun `save inserts a document that does not exist yet`(): Unit = onVertxContext {
        val doc = onboardingDoc("onboarding:${UUID.randomUUID()}")

        val saved = repo.save(doc)

        assertThat(saved.id).isEqualTo(doc.id)
        assertThat(repo.findById(doc.id)).isNotNull
    }
}
