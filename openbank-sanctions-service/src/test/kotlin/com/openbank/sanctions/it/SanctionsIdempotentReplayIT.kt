// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Real-Postgres verification for #3264: a losing concurrent write on the same `idempotency_key`
 * must replay the stored check, not surface a 500.
 *
 * `SanctionsService.screen` looks the key up before screening, but that is a check-then-act —
 * screening takes seconds, so a client retry that arrives while the first call is still in flight
 * finds nothing, screens too, and loses the INSERT to `sanctions_checks_idempotency_key_key`.
 * Before the fix that escaped as an unhandled `ConstraintViolationException` -> 500, which a
 * caller cannot tell apart from a transport fault; a domestic payment was held for hours on a
 * check that had actually completed and stored `CLEAR`.
 *
 * This has to run against a real database. The defect IS the unique index, so a mocked or
 * in-memory repository cannot express it — the constraint is the thing under test. The test also
 * pins the constraint NAME: the fix matches on it to stay narrow, so a rename must fail here
 * rather than quietly turning the replay branch into dead code.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsIdempotentReplayIT {

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

    private fun check(key: String, id: UUID = UUID.randomUUID(), name: String = "Screened Subject") = SanctionsCheck(
        id = id,
        idempotencyKey = key,
        entityType = EntityType.INDIVIDUAL,
        name = name,
        aliases = emptyList(),
        dateOfBirth = null,
        nationality = null,
        identifiers = emptyMap(),
        status = SanctionsCheckStatus.CLEAR,
        matches = emptyList(),
        overallScore = 0.0,
        checkedLists = listOf(SanctionsListType.OFAC_SDN),
        reviewedBy = null,
        reviewNote = null,
        checkedAt = Instant.parse("2026-08-02T10:58:10Z"),
        reviewedAt = null,
    )

    private fun rowCount(key: String): Long = onEventLoop {
        pool.preparedQuery("SELECT count(*) AS c FROM sanctions_checks WHERE idempotency_key = $1")
            .execute(Tuple.of(key)).awaitSuspending()
            .iterator().next().getLong("c")
    }

    private fun outboxCount(): Long = onEventLoop {
        pool.query("SELECT count(*) AS c FROM sanctions_outbox").execute().awaitSuspending()
            .iterator().next().getLong("c")
    }

    @Test
    fun `a losing writer on the same idempotency key gets the stored check back, not an exception`() {
        val key = "c05853f8-4259-4c97-b648-9c8a84082789:debtor"
        val winner = check(key, name = "Winner")

        val stored = onEventLoop { repository.saveWithEvent(winner, "SanctionChecked") }
        assertThat(stored.id).isEqualTo(winner.id)

        // The loser screened the same subject concurrently and generated its own id, exactly as
        // `SanctionsService.screen` does after its lookup came back empty.
        val loser = check(key, name = "Loser")
        val replayed = onEventLoop { repository.saveWithEvent(loser, "SanctionChecked") }

        assertThat(replayed.id)
            .describedAs("replay must return the winner's stored row, not the loser's discarded one")
            .isEqualTo(winner.id)
        assertThat(replayed.name).isEqualTo("Winner")
    }

    @Test
    fun `a replay writes neither a second row nor a duplicate outbox event`() {
        val key = "payment-1:creditor"

        onEventLoop { repository.saveWithEvent(check(key), "SanctionChecked") }
        assertThat(outboxCount()).isEqualTo(1)

        onEventLoop { repository.saveWithEvent(check(key), "SanctionChecked") }

        assertThat(rowCount(key)).describedAs("one key, one stored check").isEqualTo(1)
        assertThat(outboxCount())
            .describedAs("the loser's transaction rolled back, so it must not emit a second event")
            .isEqualTo(1)
    }

    /**
     * The guard must be narrow. A primary-key collision is a DIFFERENT failure — the
     * persist-vs-merge trap on an application-assigned `@Id` (ADR-0126 D3) — and swallowing it as
     * "an idempotent replay" would hide a real bug behind a successful-looking response. Without
     * this case the fix could be written as a blanket `catch (ex: Exception)` and still pass every
     * other assertion in this class.
     */
    @Test
    fun `a primary-key collision still propagates and is not mistaken for a replay`() {
        val sharedId = UUID.randomUUID()
        onEventLoop { repository.saveWithEvent(check("key-a", id = sharedId), "SanctionChecked") }

        assertThatThrownBy {
            onEventLoop { repository.saveWithEvent(check("key-b", id = sharedId), "SanctionChecked") }
        }.describedAs("same id, different key — a pkey violation, not an idempotent replay")
            .isInstanceOf(Exception::class.java)

        assertThat(rowCount("key-b")).isEqualTo(0)
    }
}
