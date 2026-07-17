// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresTestResource
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.lock.ClusterLock
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Holds the current transaction open for [seconds] via Postgres's own `pg_sleep`, not
 * `kotlinx.coroutines.delay` — `delay` resumes on whatever thread its scheduler picks, which
 * breaks the Vert.x duplicated-context affinity Hibernate Reactive's session needs, throwing
 * `IllegalStateException` from Vert.x's own context assertions. A native query is just another
 * suspend DB call on the same context, so it holds the surrounding
 * [ClusterLock.tryRunExclusively] transaction (and its advisory lock) open for real, measured
 * wall-clock time without ever leaving that context.
 */
private suspend fun holdTransactionOpen(seconds: Double) {
    Panache.getSession().chain { session ->
        session.createNativeQuery<Any>("SELECT pg_sleep($seconds)").singleResult
    }.awaitSuspending()
}

/**
 * Regression coverage for #1201 proposed fix 2: [ClusterLock.tryRunExclusively] against a real
 * Postgres proves the `pg_try_advisory_xact_lock` claim actually excludes a second concurrent
 * caller for the same job name, does not cross-block unrelated job names, and releases cleanly
 * (a stale-lock/never-releases bug here would silently wedge a scheduler forever, since there is
 * no separate reclaim path the way there is for the outbox claim's DISPATCHING status).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ClusterLockIT {

    @Inject
    lateinit var clusterLock: ClusterLock

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @Test
    fun `two concurrent calls for the same job name never both run — the loser gets null`() {
        val jobName = "test.job.${Ids.newId()}"
        var executions = 0
        // A fixed head-start delay before launching the second call is flaky: how long `a`
        // actually takes to reach its lock-acquire statement varies with connection-pool /
        // Testcontainers overhead, so a delay picked too short lets `b` win the race instead of
        // losing it as intended, spuriously failing the exclusivity assertion below (observed:
        // 1 flaky failure in 3 manual reruns before this fix). Signal from inside the winner's
        // block instead — `b` is only launched once `a` has *provably* already acquired the lock
        // and is mid-transaction, which is deterministic regardless of scheduling jitter.
        val lockHeld = CompletableDeferred<Unit>()

        val (first, second) = runBlocking {
            val a = async(Dispatchers.IO) {
                onEventLoop {
                    clusterLock.tryRunExclusively(jobName) {
                        executions++
                        lockHeld.complete(Unit)
                        holdTransactionOpen(0.3)
                        "winner"
                    }
                }
            }
            lockHeld.await()
            val b = async(Dispatchers.IO) { onEventLoop { clusterLock.tryRunExclusively(jobName) { "loser" } } }
            awaitAll(a, b)
        }

        assertThat(executions).describedAs("block() body ran exactly once across both calls").isEqualTo(1)
        assertThat(listOf(first, second).count { it != null })
            .describedAs("exactly one of the two concurrent calls acquired the lock")
            .isEqualTo(1)
    }

    @Test
    fun `different job names do not block each other`() {
        val jobA = "test.job.a.${Ids.newId()}"
        val jobB = "test.job.b.${Ids.newId()}"

        val (resultA, resultB) = runBlocking {
            val a = async(Dispatchers.IO) {
                onEventLoop {
                    clusterLock.tryRunExclusively(jobA) {
                        holdTransactionOpen(0.2)
                        "a"
                    }
                }
            }
            val b = async(Dispatchers.IO) {
                onEventLoop {
                    clusterLock.tryRunExclusively(jobB) {
                        holdTransactionOpen(0.2)
                        "b"
                    }
                }
            }
            awaitAll(a, b)
        }

        assertThat(resultA).isEqualTo("a")
        assertThat(resultB).isEqualTo("b")
    }

    @Test
    fun `the lock releases after commit — a later sequential call for the same job succeeds`() {
        val jobName = "test.job.${Ids.newId()}"

        val first = onEventLoop { clusterLock.tryRunExclusively(jobName) { "first" } }
        val second = onEventLoop { clusterLock.tryRunExclusively(jobName) { "second" } }

        assertThat(first).isEqualTo("first")
        assertThat(second)
            .describedAs("the first call's transaction committed, so its advisory lock must already be released")
            .isEqualTo("second")
    }
}
