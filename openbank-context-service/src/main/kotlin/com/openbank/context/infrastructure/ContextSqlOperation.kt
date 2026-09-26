// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.smallrye.mutiny.Uni
import io.vertx.core.Vertx
import kotlinx.coroutines.CancellationException
import org.hibernate.reactive.mutiny.Mutiny
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Controlled operation lifecycle. Every SQL statement (including flush) must use [sql].
 * Each callback submits one statement; callers must not cancel or time out its returned Uni.
 * Cancellation stops later guarded statements and retains observation until server timeout.
 * A legacy whole-block bridge cannot stop statements chained inside its callback.
 * It does not send PostgreSQL cancellation. Acquisition and cleanup are not time bounded here.
 */
internal class ContextSqlOperation private constructor(
    private val session: Mutiny.Session,
    private val deadlineNanos: Long,
    private val cancelled: AtomicBoolean,
) {
    private val activeSql = AtomicBoolean()

    fun <T> sql(statement: (Mutiny.Session) -> Uni<T>): Uni<T> = Uni.createFrom().deferred<T> {
        val remaining = remainingMillis()
        check(activeSql.compareAndSet(false, true)) { "Context SQL statements must run sequentially" }
        session.createNativeQuery("select set_config('statement_timeout', :timeout, true)", String::class.java)
            .setParameter("timeout", "${remaining}ms").singleResult.flatMap {
                remainingMillis()
                statement(session)
            }.invoke(java.util.function.Consumer<T> { remainingMillis() })
            .onItemOrFailure().invoke(java.util.function.BiConsumer<T?, Throwable?> { _, _ -> activeSql.set(false) })
    }

    private fun remainingMillis(): Long {
        if (cancelled.get()) throw CancellationException("Context SQL operation cancelled")
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) throw TimeoutException("Context SQL operation deadline expired")
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
    }

    companion object {
        /**
         * The retained internal subscriber owns rollback/close after downstream cancellation.
         * The final work item is the commit boundary: cancellation after that point cannot
         * guarantee rollback, and the caller must treat the commit outcome as indeterminate.
         * RLS setup belongs in the first guarded statement, inside this transaction.
         */
        fun <T> execute(
            sessions: Mutiny.SessionFactory,
            budgetMs: Int,
            work: (ContextSqlOperation) -> Uni<T>,
        ): Uni<T> {
            require(budgetMs in 1..MAX_OPERATION_BUDGET_MS) { "Context SQL budget must be between 1 and 60000 ms" }
            return Uni.createFrom().emitter<T> { downstream ->
                val context = checkNotNull(Vertx.currentContext()) { "Context SQL requires a Vertx context" }
                val cancelled = AtomicBoolean()
                val delivered = AtomicBoolean()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.toLong())
                var transaction: Mutiny.Transaction? = null
                val phase = AtomicInteger(WORKING)
                downstream.onTermination {
                    if (!delivered.get()) {
                        phase.compareAndSet(WORKING, CANCELLED)
                        cancelled.set(true)
                        context.runOnContext {
                            if (phase.get() == CANCELLED) transaction?.markForRollback()
                        }
                    }
                }
                sessions.openSession().flatMap { session ->
                    session.withTransaction<T> { tx ->
                        transaction = tx
                        val operation = ContextSqlOperation(session, deadline, cancelled)
                        Uni.createFrom().deferred<T> {
                            operation.remainingMillis()
                            work(operation)
                        }.invoke(
                            java.util.function.Consumer<T> {
                                operation.remainingMillis()
                                check(!operation.activeSql.get()) {
                                    "Context SQL work finished with an active statement"
                                }
                                if (!phase.compareAndSet(WORKING, COMMITTING)) {
                                    throw CancellationException("Context SQL operation cancelled before commit")
                                }
                            },
                        )
                    }.onTermination().call { session.close() }
                }.subscribe().with(
                    { result ->
                        delivered.set(true)
                        if (!cancelled.get()) downstream.complete(result)
                    },
                    { failure ->
                        delivered.set(true)
                        if (!cancelled.get()) downstream.fail(failure)
                    },
                )
            }
        }

        private const val MAX_OPERATION_BUDGET_MS = 60_000
        private const val WORKING = 0
        private const val CANCELLED = 1
        private const val COMMITTING = 2
    }
}
