// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsRepositoryImpl
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Real-Postgres verification that a manual review UPDATES the stored check.
 *
 * `SanctionsService.review` loads a check, copies it with the review fields set, and hands the
 * result back to the repository. The aggregate's `@Id` is application-assigned
 * (`SanctionsCheckEntity.id`, no `@GeneratedValue`), so a repository `save` that calls `persist`
 * schedules an INSERT and never an UPDATE — Hibernate cannot tell a transient instance from a
 * detached one when the id is already non-null. Every review therefore died at flush with
 * `duplicate key value violates unique constraint "sanctions_checks_pkey"` (ADR-0126 D3; the same
 * defect made every consent-service transition 500 in #1521/#1553 and broke standing-order cancel
 * in #2079).
 *
 * This must run against a real database. A mocked repository cannot express it: the failure is
 * Hibernate's INSERT-vs-UPDATE decision at flush time, not anything visible in the port's
 * signature — which is exactly why the service's unit tests passed throughout.
 *
 * The test drives the real `SanctionsUseCase` bean rather than the repository directly, so it also
 * covers the wiring: `review` must reach the update path, and `screen` must keep reaching the
 * insert path (`SanctionsIdempotentReplayIT` owns the latter).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsReviewUpdateIT {

    @Inject
    lateinit var useCase: SanctionsUseCase

    @Inject
    lateinit var repository: SanctionsRepositoryImpl

    @Inject
    lateinit var pool: PgPool

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @BeforeEach
    fun clearTables() {
        onEventLoop {
            pool.query("DELETE FROM sanctions_checks").execute().awaitSuspending()
            pool.query("DELETE FROM sanctions_outbox").execute().awaitSuspending()
        }
    }

    private fun hit(key: String, id: UUID = UUID.randomUUID()) = SanctionsCheck(
        id = id,
        idempotencyKey = key,
        entityType = EntityType.INDIVIDUAL,
        name = "Screened Subject",
        aliases = emptyList(),
        dateOfBirth = null,
        nationality = null,
        identifiers = emptyMap(),
        status = SanctionsCheckStatus.POTENTIAL_HIT,
        matches = emptyList(),
        overallScore = 0.7,
        checkedLists = listOf(SanctionsListType.OFAC_SDN),
        reviewedBy = null,
        reviewNote = null,
        checkedAt = Instant.parse("2026-08-02T10:58:10Z"),
        reviewedAt = null,
    )

    private fun rowCount(): Long = onEventLoop {
        pool.query("SELECT count(*) AS c FROM sanctions_checks").execute().awaitSuspending()
            .iterator().next().getLong("c")
    }

    private fun outboxCount(): Long = onEventLoop {
        pool.query("SELECT count(*) AS c FROM sanctions_outbox").execute().awaitSuspending()
            .iterator().next().getLong("c")
    }

    private fun storedStatus(id: UUID): String = onEventLoop {
        pool.preparedQuery("SELECT status FROM sanctions_checks WHERE id = $1")
            .execute(Tuple.of(id)).awaitSuspending()
            .iterator().next().getString("status")
    }

    @Test
    fun `reviewing a potential hit updates the stored row instead of colliding with its own primary key`() {
        val stored = onEventLoop { repository.saveWithEvent(hit("review-key-1"), "SanctionChecked") }

        val reviewed = onEventLoop {
            useCase.review(
                ReviewCommand(
                    checkId = stored.id,
                    reviewedBy = "operator-1",
                    note = "false positive — different date of birth",
                    newStatus = SanctionsCheckStatus.CLEAR,
                ),
            )
        }

        assertThat(reviewed.id).isEqualTo(stored.id)
        assertThat(reviewed.status).isEqualTo(SanctionsCheckStatus.CLEAR)
        assertThat(reviewed.reviewedBy).isEqualTo("operator-1")
        assertThat(reviewed.reviewNote).isEqualTo("false positive — different date of birth")
        assertThat(reviewed.reviewedAt).isNotNull()

        assertThat(rowCount())
            .describedAs("a review must UPDATE the check, never insert a second copy of it")
            .isEqualTo(1)
        assertThat(storedStatus(stored.id))
            .describedAs("the transition must be durable, not just present on the returned object")
            .isEqualTo("CLEAR")
    }

    @Test
    fun `a review emits its own outbox event in the same transaction as the update`() {
        val stored = onEventLoop { repository.saveWithEvent(hit("review-key-2"), "SanctionChecked") }
        assertThat(outboxCount()).isEqualTo(1)

        onEventLoop {
            useCase.review(
                ReviewCommand(
                    checkId = stored.id,
                    reviewedBy = "operator-2",
                    note = "confirmed match, escalating",
                    newStatus = SanctionsCheckStatus.ESCALATED,
                ),
            )
        }

        assertThat(outboxCount())
            .describedAs("the review decision is an event downstream AML/audit consumers must see")
            .isEqualTo(2)
        assertThat(storedStatus(stored.id)).isEqualTo("ESCALATED")
    }

    /**
     * Two reviews in a row: the second must update the row the first left behind. A fix that
     * merely made the first review work — for example by deleting and re-inserting — would pass
     * the first test and fail this one.
     */
    @Test
    fun `a second review updates the row the first one left, and never accumulates rows`() {
        val stored = onEventLoop { repository.saveWithEvent(hit("review-key-3"), "SanctionChecked") }

        onEventLoop {
            useCase.review(
                ReviewCommand(stored.id, "operator-1", "cleared in error", SanctionsCheckStatus.CLEAR),
            )
        }
        val second = onEventLoop {
            useCase.review(
                ReviewCommand(stored.id, "operator-2", "re-opened, it is a real match", SanctionsCheckStatus.HIT),
            )
        }

        assertThat(second.status).isEqualTo(SanctionsCheckStatus.HIT)
        assertThat(second.reviewedBy).isEqualTo("operator-2")
        assertThat(rowCount()).isEqualTo(1)
        assertThat(storedStatus(stored.id)).isEqualTo("HIT")
    }
}
