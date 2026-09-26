// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.repository

import com.openbank.document.application.port.out.DuplicateCeremonyException
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.Signer
import com.openbank.document.domain.model.SignerStatus
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
 * Regression coverage for the optimistic-lock defect found in review: `save`/`saveWithOutbox` used
 * to re-`find()` the entity fresh inside the same transaction and blindly overwrite it, so
 * `SignatureCeremonyEntity`'s `@Version` check never saw the staleness of the read the caller
 * actually acted on — two concurrent `recordDecision` calls could silently lost-update each other
 * on a signed, PAdES-sealed ceremony. `save`/`saveWithOutbox` now `merge()` an entity carrying the
 * version read at use-case time, so Hibernate's own version check fires for real.
 *
 * Needs a real Postgres (Testcontainers, [PostgresRedisTestResource]) — this is genuine
 * database-level optimistic-locking behaviour, not something a mocked repository can exercise.
 *
 * `CeremonyRepositoryImpl` is reactive (`Panache.withSession/withTransaction`), so its suspend
 * calls must run on a Vert.x duplicated context — a plain `runBlocking` test thread has none
 * (mirrors `openbank-anacredit-service`'s `LoanStageProjectionRepositoryIT`).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class CeremonyRepositoryImplIT {

    @Inject
    lateinit var repo: CeremonyRepositoryImpl

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun newCeremony() = SignatureCeremony(
        id = UUID.randomUUID(),
        documentId = UUID.randomUUID(),
        signers = listOf(Signer(partyRef = "party-1", order = 1, status = SignerStatus.PENDING, signedAt = null)),
        status = CeremonyStatus.PENDING,
        signatureLevel = SignatureLevel.ADVANCED,
        createdAt = Instant.now(),
    )

    @Test
    fun `a second save based on a stale read is rejected, not silently applied`(): Unit = onVertxContext {
        val created = repo.save(newCeremony())

        // Two "concurrent" reads of the same row — both see version 0.
        val readA = repo.findById(created.id)!!
        val readB = repo.findById(created.id)!!
        assertThat(readA.version).isEqualTo(readB.version)

        // A commits first: its write is based on the version it read, so it succeeds and the row
        // advances to version 1.
        val savedA = repo.save(readA.copy(status = CeremonyStatus.DECLINED))
        assertThat(savedA.version).isGreaterThan(readA.version)

        // B's write is still based on the ORIGINAL (now-stale) version 0 read — it must be rejected,
        // not silently overwrite A's already-committed decision. The whole test body already runs
        // on a Vert.x context via the outer onVertxContext — nesting a second subscribeAndAwait()
        // here would throw "must not be called on an event loop", so this is a plain try/catch
        // around the suspend call rather than assertThatThrownBy (which expects a blocking callable).
        val rejected = try {
            repo.save(readB.copy(status = CeremonyStatus.COMPLETED))
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertThat(rejected).isNotNull
        assertThat(rejected!!.message).contains("concurrently modified")

        // The row must still reflect A's write, not B's rejected one.
        val final = repo.findById(created.id)!!
        assertThat(final.status).isEqualTo(CeremonyStatus.DECLINED)
    }

    @Test
    fun `save on a fresh ceremony inserts and read-back round-trips`(): Unit = onVertxContext {
        val ceremony = newCeremony()

        val saved = repo.save(ceremony)

        assertThat(saved.id).isEqualTo(ceremony.id)
        val reloaded = repo.findById(ceremony.id)
        assertThat(reloaded).isNotNull
        assertThat(reloaded!!.signers).isEqualTo(ceremony.signers)
    }

    @Test
    fun `at most one active ceremony per document, but a new one is allowed after the prior goes terminal`(): Unit =
        onVertxContext {
            val documentId = UUID.randomUUID()
            val first = repo.save(newCeremony().copy(documentId = documentId))

            // A second ACTIVE ceremony for the same document is rejected by the partial unique index.
            val rejected = try {
                repo.save(newCeremony().copy(documentId = documentId))
                null
            } catch (e: DuplicateCeremonyException) {
                e
            }
            assertThat(rejected).isNotNull
            assertThat(repo.findByDocumentId(documentId)!!.id).isEqualTo(first.id)

            // Once the first ceremony reaches a terminal DECLINED state it leaves the active index,
            // so a fresh ceremony for the same document IS allowed (a legitimate re-attempt).
            repo.save(repo.findById(first.id)!!.copy(status = CeremonyStatus.DECLINED))
            val retry = repo.save(newCeremony().copy(documentId = documentId))
            assertThat(retry.id).isNotEqualTo(first.id)
        }
}
