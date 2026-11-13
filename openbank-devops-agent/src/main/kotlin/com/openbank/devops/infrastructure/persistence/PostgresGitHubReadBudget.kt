// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.persistence

import com.openbank.devops.application.port.out.GitHubReadBudget
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant

/** Database-clock rolling-hour budget shared by all readers and replicas of this agent. */
@ApplicationScoped
class PostgresGitHubReadBudget(private val sf: Mutiny.SessionFactory) : GitHubReadBudget {
    override suspend fun reserve(observer: Boolean): Boolean = sf.withTransaction { s ->
        s.createNativeQuery<Int>(
            "UPDATE github_read_budget SET reservation_serial = reservation_serial + 1 WHERE id = 1",
        ).executeUpdate().flatMap {
            // CURRENT_TIMESTAMP predates a lock wait: sample the database clock only after admission.
            s.createNativeQuery("SELECT clock_timestamp()", Instant::class.java).singleResult
        }.flatMap { observedNow ->
            s.createNativeQuery<Int>(
                "DELETE FROM github_read_requests WHERE requested_at <= :cutoff",
            ).setParameter("cutoff", observedNow.minusSeconds(ROLLING_HOUR_SECONDS)).executeUpdate().flatMap {
                s.createNativeQuery<Int>(
                    """INSERT INTO github_read_requests (id, requested_at, observer)
                        SELECT :id, :observed, :observer
                        WHERE (SELECT COUNT(*) FROM github_read_requests) < 20
                        AND (:observer = false OR
                             (SELECT COUNT(*) FROM github_read_requests WHERE observer = true) < 18)
                        AND (SELECT blocked_until IS NULL OR blocked_until <= :observed
                             FROM github_read_budget WHERE id = 1)
                    """.trimIndent(),
                ).setParameter("id", Ids.newId()).setParameter("observer", observer)
                    .setParameter("observed", observedNow).executeUpdate()
            }
        }
    }.map { it == 1 }.subscribeAsCompletionStage().await()

    private companion object {
        const val ROLLING_HOUR_SECONDS = 3600L
    }

    override suspend fun deferUntil(until: Instant) {
        sf.withTransaction { s ->
            s.createNativeQuery<Int>(
                """UPDATE github_read_budget
                    SET blocked_until = GREATEST(COALESCE(blocked_until, CURRENT_TIMESTAMP), :until)
                    WHERE id = 1
                """.trimIndent(),
            ).setParameter("until", until).executeUpdate()
        }.subscribeAsCompletionStage().await()
    }
}
