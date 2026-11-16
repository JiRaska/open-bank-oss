// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.SignatureIntent
import com.openbank.lending.application.port.out.SignatureIntents
import com.openbank.libs.domain.identifiers.LoanApplicationId
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

@ApplicationScoped
class SignatureIntentRepository(private val sessions: Mutiny.SessionFactory) : SignatureIntents {
    override fun stage(intent: SignatureIntent): Uni<SignatureIntent> = sessions.withTransaction { session ->
        session.createNativeQuery<Int>(INSERT)
            .setParameter("operation", intent.id)
            .setParameter("application", intent.applicationId.value)
            .setParameter("revision", intent.revisionId)
            .setParameter("content", intent.revisionContentHash)
            .setParameter("artefact", intent.artefactHash)
            .setParameter("actor", intent.actor)
            .setParameter("synthetic", intent.synthetic)
            .executeUpdate()
            .flatMap {
                session.createNativeQuery(LOAD, Array<Any>::class.java)
                    .setParameter("application", intent.applicationId.value).singleResult
            }.map { row -> row.toIntent() }
    }

    override fun find(applicationId: LoanApplicationId): Uni<SignatureIntent?> = sessions.withSession { session ->
        session.createNativeQuery(LOAD, Array<Any>::class.java)
            .setParameter("application", applicationId.value).singleResultOrNull
            .map { it?.toIntent() }
    }

    override fun complete(operationId: UUID): Uni<Unit> = sessions.withTransaction { session ->
        session.createNativeQuery<Int>(COMPLETE).setParameter("operation", operationId).executeUpdate().map { changed ->
            check(changed == 1) { "Signature intent did not complete" }
        }
    }

    override fun claimDue(limit: Int): Uni<List<SignatureIntent>> = sessions.withTransaction { session ->
        session.createNativeQuery(CLAIM, Array<Any>::class.java)
            .setParameter("limit", limit)
            .resultList.map { rows -> rows.map { it.toIntent() } }
    }

    private fun Array<Any>.toIntent() = SignatureIntent(
        this[OPERATION_COLUMN] as UUID,
        LoanApplicationId(this[APPLICATION_COLUMN] as UUID),
        this[REVISION_COLUMN] as UUID,
        this[CONTENT_HASH_COLUMN] as String,
        this[ARTEFACT_COLUMN] as String,
        this[ACTOR_COLUMN] as String,
        this[SYNTHETIC_COLUMN] as Boolean,
    )

    private companion object {
        const val OPERATION_COLUMN = 0
        const val APPLICATION_COLUMN = 1
        const val REVISION_COLUMN = 2
        const val CONTENT_HASH_COLUMN = 3
        const val ARTEFACT_COLUMN = 4
        const val ACTOR_COLUMN = 5
        const val SYNTHETIC_COLUMN = 6

        const val COLUMNS =
            "operation_id, application_id, revision_id, revision_content_hash, artefact_hash, actor, synthetic"
        const val LOAD = "SELECT $COLUMNS FROM lending_signature_intent WHERE application_id = :application"
        const val INSERT = """
            INSERT INTO lending_signature_intent ($COLUMNS)
            VALUES (:operation, :application, :revision, :content, :artefact, :actor, :synthetic)
            ON CONFLICT (application_id) DO NOTHING
        """
        const val COMPLETE = """
            UPDATE lending_signature_intent SET completed_at = clock_timestamp()
            WHERE operation_id = :operation AND completed_at IS NULL
        """
        const val CLAIM = """
            WITH due AS (
                SELECT i.application_id FROM lending_signature_intent i
                JOIN loan_application a ON a.id = i.application_id AND a.status = 'AWAITING_SIGNATURE'
                WHERE i.completed_at IS NULL AND i.next_attempt_at <= clock_timestamp()
                ORDER BY i.next_attempt_at, i.application_id
                LIMIT :limit FOR UPDATE OF i SKIP LOCKED
            ), claimed AS (
                UPDATE lending_signature_intent i SET next_attempt_at = clock_timestamp() + interval '1 minute'
                FROM due WHERE i.application_id = due.application_id
                RETURNING i.*
            )
            SELECT $COLUMNS FROM claimed
        """
    }
}
