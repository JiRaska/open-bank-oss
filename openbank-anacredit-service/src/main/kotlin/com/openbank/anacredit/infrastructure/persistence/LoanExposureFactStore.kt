// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.persistence

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.OffsetDateTime
import java.util.UUID

data class LoanExposureFact(
    val eventId: UUID,
    val sourceReference: String,
    val loanId: UUID,
    val effectiveAt: OffsetDateTime,
    val payload: String,
)

@ApplicationScoped
class LoanExposureFactStore(private val sessionFactory: Mutiny.SessionFactory) {

    suspend fun append(fact: LoanExposureFact): Unit = sessionFactory.withTransaction { session ->
        // Bind text so the reactive driver leaves the original JSON numeric tokens intact.
        session.createNativeQuery<Any>(
            """
            INSERT INTO loan_exposure_facts (event_id, source_reference, loan_id, effective_at, payload)
            VALUES (:eventId, :sourceReference, :loanId, :effectiveAt, CAST(CAST(:payload AS text) AS jsonb))
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )
            .setParameter("eventId", fact.eventId)
            .setParameter("sourceReference", fact.sourceReference)
            .setParameter("loanId", fact.loanId)
            .setParameter("effectiveAt", fact.effectiveAt)
            .setParameter("payload", fact.payload)
            .executeUpdate()
            .flatMap { inserted ->
                if (inserted == 1) Uni.createFrom().item(Unit) else verifyReplay(session, fact)
            }
    }.awaitSuspending()

    private fun verifyReplay(session: Mutiny.Session, fact: LoanExposureFact): Uni<Unit> = session.createNativeQuery(
        """
        SELECT payload = CAST(CAST(:payload AS text) AS jsonb)
        FROM loan_exposure_facts
        WHERE event_id = :eventId AND source_reference = :sourceReference
          AND loan_id = :loanId AND effective_at = :effectiveAt
        """.trimIndent(),
        Boolean::class.javaObjectType,
    )
        .setParameter("eventId", fact.eventId)
        .setParameter("sourceReference", fact.sourceReference)
        .setParameter("loanId", fact.loanId)
        .setParameter("effectiveAt", fact.effectiveAt)
        .setParameter("payload", fact.payload)
        .singleResultOrNull
        .map { matches ->
            require(matches == true) { "Conflicting lending exposure fact replay" }
        }
}
