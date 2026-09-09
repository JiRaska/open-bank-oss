// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.infrastructure.persistence.repository

import com.openbank.document.domain.model.DisclosureSnapshot
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.it.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Real-Postgres proof of V12's request-id uniqueness and insert-only repository semantics. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class DisclosureSnapshotRepositoryImplIT {
    @Inject
    lateinit var repo: DisclosureSnapshotRepositoryImpl

    @Inject
    lateinit var documentRepo: DocumentRepositoryImpl

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    @Test
    fun `request replay returns original immutable row`(): Unit = onVertxContext {
        val requestId = UUID.randomUUID()
        val firstSource = documentRepo.save(sourceDocument())
        val secondSource = documentRepo.save(sourceDocument())
        val first = snapshot(requestId, firstSource.id, "a".repeat(64))
        val conflicting = snapshot(requestId, secondSource.id, "b".repeat(64))

        assertThat(repo.createOrFind(first)).isEqualTo(first)
        assertThat(repo.createOrFind(conflicting)).isEqualTo(first)
        assertThat(repo.findById(first.id)).isEqualTo(first)
        assertThat(repo.findByRequestId(requestId)).isEqualTo(first)
    }

    @Test
    fun `database rejects mutation of persisted snapshot evidence`(): Unit = onVertxContext {
        val source = documentRepo.save(sourceDocument())
        val persisted = repo.createOrFind(snapshot(UUID.randomUUID(), source.id, "d".repeat(64)))

        val rejected = try {
            Panache.withTransaction {
                Panache.getSession().flatMap { session ->
                    session.createNativeQuery<Any>(
                        "UPDATE disclosure_snapshots SET sha256 = :digest WHERE id = :id",
                    )
                        .setParameter("digest", "e".repeat(64))
                        .setParameter("id", persisted.id)
                        .executeUpdate()
                }
            }.awaitSuspending()
            null
        } catch (e: RuntimeException) {
            e
        }

        assertThat(rejected).isNotNull
        assertThat(repo.findById(persisted.id)!!.sha256).isEqualTo("d".repeat(64))
    }

    private fun sourceDocument(): Document {
        val id = UUID.randomUUID()
        return Document(
            id,
            "SIGNED_AGREEMENT",
            "1",
            "c".repeat(64),
            "documents/$id",
            "application/pdf",
            42,
            DocumentStatus.SIGNED,
            emptyMap(),
            UUID.randomUUID().toString(),
            null,
            null,
            null,
            Instant.parse("2026-09-09T09:00:00Z"),
        )
    }

    private fun snapshot(requestId: UUID, sourceId: UUID, digest: String): DisclosureSnapshot {
        val id = UUID.randomUUID()
        return DisclosureSnapshot(
            id,
            requestId,
            sourceId,
            UUID.randomUUID().toString(),
            digest,
            digest,
            "documents/disclosures/$id/$digest",
            "application/pdf",
            42,
            Instant.parse("2026-09-09T10:00:00Z"),
        )
    }
}
