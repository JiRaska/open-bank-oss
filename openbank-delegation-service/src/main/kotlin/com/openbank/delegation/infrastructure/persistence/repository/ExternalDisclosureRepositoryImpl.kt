// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.ExternalDisclosureRepository
import com.openbank.delegation.domain.model.ExternalDisclosure
import com.openbank.delegation.infrastructure.persistence.entity.ExternalDisclosureEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Serialises state transitions on the disclosure row. A successful new view gets both its
 * incremented counter and its transparency timestamp in the same transaction.
 */
@ApplicationScoped
class ExternalDisclosureRepositoryImpl :
    ExternalDisclosureRepository,
    PanacheRepository<ExternalDisclosureEntity> {

    override suspend fun issue(disclosure: ExternalDisclosure): ExternalDisclosure = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.persist(ExternalDisclosureEntity.fromDomain(disclosure)).replaceWith(disclosure)
        }
    }.awaitSuspending()

    override suspend fun findByLinkSecretHash(linkSecretHash: String): ExternalDisclosure? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            findOne(session, FIND_BY_LINK_HASH_SQL, linkSecretHash)
                .flatMap { entity -> entity?.let { toDomain(session, it) } ?: Uni.createFrom().nullItem() }
        }
    }.awaitSuspending()

    override suspend fun mutateByLinkSecretHash(
        linkSecretHash: String,
        transition: (ExternalDisclosure) -> ExternalDisclosure,
    ): ExternalDisclosure? = mutate(LOCK_BY_LINK_HASH_SQL, linkSecretHash, transition)

    override suspend fun mutateById(
        id: UUID,
        transition: (ExternalDisclosure) -> ExternalDisclosure,
    ): ExternalDisclosure? = mutate(LOCK_BY_ID_SQL, id, transition)

    private suspend fun <T> mutate(
        lockSql: String,
        parameter: T,
        transition: (ExternalDisclosure) -> ExternalDisclosure,
    ): ExternalDisclosure? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            findOne(session, lockSql, parameter).flatMap { entity ->
                entity?.let { mutateLocked(session, it, transition) } ?: Uni.createFrom().nullItem()
            }
        }
    }.awaitSuspending()

    private fun mutateLocked(
        session: Mutiny.Session,
        entity: ExternalDisclosureEntity,
        transition: (ExternalDisclosure) -> ExternalDisclosure,
    ): Uni<ExternalDisclosure> = toDomain(session, entity).flatMap { current ->
        val next = transition(current)
        require(next.id == current.id) { "external disclosure identity cannot change" }
        require(next.viewedAt.size >= current.viewedAt.size) { "external disclosure views are append-only" }
        require(next.viewedAt.drop(current.viewedAt.size).size <= 1) {
            "external disclosure transitions can record at most one view"
        }
        entity.apply(next)
        persistNewView(session, current, next)
            .flatMap { session.merge(entity) }
            .replaceWith(next)
    }

    private fun persistNewView(
        session: Mutiny.Session,
        current: ExternalDisclosure,
        next: ExternalDisclosure,
    ): Uni<Int> {
        val newView = next.viewedAt.drop(current.viewedAt.size).singleOrNull()
            ?: return Uni.createFrom().item(0)
        return session.createNativeQuery<Any>(INSERT_VIEW_SQL)
            .setParameter("disclosureId", current.id)
            .setParameter("viewedAt", newView)
            .executeUpdate()
    }

    private fun <T> findOne(session: Mutiny.Session, sql: String, parameter: T): Uni<ExternalDisclosureEntity?> =
        session
            .createNativeQuery(sql, ExternalDisclosureEntity::class.java)
            .setParameter("value", parameter)
            .resultList
            .map { it.firstOrNull() }

    private fun toDomain(session: Mutiny.Session, entity: ExternalDisclosureEntity): Uni<ExternalDisclosure> = session
        .createNativeQuery(VIEWS_SQL, OffsetDateTime::class.java)
        .setParameter("disclosureId", entity.id)
        .resultList
        .map { viewedAt -> entity.toDomain(viewedAt) }

    private companion object {
        const val FIND_BY_LINK_HASH_SQL =
            "select * from delegation_external_disclosures where link_secret_hash = :value"
        const val LOCK_BY_LINK_HASH_SQL = "$FIND_BY_LINK_HASH_SQL for update"
        const val LOCK_BY_ID_SQL = "select * from delegation_external_disclosures where id = :value for update"
        const val VIEWS_SQL = """
            select viewed_at from delegation_external_disclosure_views
            where disclosure_id = :disclosureId order by viewed_at asc, id asc
        """
        const val INSERT_VIEW_SQL = """
            insert into delegation_external_disclosure_views (disclosure_id, viewed_at)
            values (:disclosureId, :viewedAt)
        """
    }
}
