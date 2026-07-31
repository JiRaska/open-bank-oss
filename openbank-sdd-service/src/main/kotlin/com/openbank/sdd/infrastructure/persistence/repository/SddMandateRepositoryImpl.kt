// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.persistence.repository

import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.infrastructure.persistence.entity.SddMandateEntity
import com.openbank.sdd.infrastructure.persistence.mapper.SddMandateMapper
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

@ApplicationScoped
class SddMandateRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: SddMandateMapper,
) : SddMandateRepository {

    @WithTransaction
    override fun save(mandate: SddMandate): Uni<SddMandate> = sf.withTransaction { s ->
        // Upsert: merge so lifecycle transitions on an existing row update rather than insert.
        s.merge(mapper.toEntity(mandate)).map { mapper.toDomain(it) }
    }

    @WithSession
    override fun findById(id: UUID): Uni<SddMandate?> = sf.withSession { s -> s.find(SddMandateEntity::class.java, id) }
        .map { it?.let(mapper::toDomain) }

    @WithSession
    override fun findByReference(creditorIdentifier: String, umr: String): Uni<SddMandate?> = sf.withSession { s ->
        s.createQuery(
            "FROM SddMandateEntity WHERE creditorIdentifier = :c AND umr = :u",
            SddMandateEntity::class.java,
        ).setParameter("c", creditorIdentifier).setParameter("u", umr)
            .setMaxResults(1).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun listForAccount(accountId: UUID): Uni<List<SddMandate>> = sf.withSession { s ->
        s.createQuery(
            "FROM SddMandateEntity WHERE accountId = :a ORDER BY createdAt DESC",
            SddMandateEntity::class.java,
        ).setParameter("a", accountId).resultList
    }.map { list -> list.map(mapper::toDomain) }

    @WithSession
    override fun listLive(): Uni<List<SddMandate>> = sf.withSession { s ->
        s.createQuery(
            "FROM SddMandateEntity WHERE status IN (:statuses) ORDER BY createdAt ASC",
            SddMandateEntity::class.java,
        ).setParameter("statuses", listOf(MandateStatus.ACTIVE, MandateStatus.SUSPENDED)).resultList
    }.map { list -> list.map(mapper::toDomain) }

    @WithSession
    override fun findRecent(status: String?, limit: Int): Uni<List<SddMandate>> = sf.withSession { s ->
        val query = if (status != null) {
            s.createQuery(
                "FROM SddMandateEntity WHERE status = :st ORDER BY createdAt DESC",
                SddMandateEntity::class.java,
            ).setParameter("st", MandateStatus.valueOf(status))
        } else {
            s.createQuery("FROM SddMandateEntity ORDER BY createdAt DESC", SddMandateEntity::class.java)
        }
        query.setMaxResults(limit).resultList
    }.map { list -> list.map(mapper::toDomain) }
}
