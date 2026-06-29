// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.application.port.out.ProductRepository
import com.openbank.productcatalog.domain.Product
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

/**
 * Postgres-backed product repository (ADR-0105 P1). Replaces the in-memory `ConcurrentHashMap` so the
 * catalogue's canonical product identity is durable across restarts. Reactive Panache (the fleet
 * standard — openbank-libs is reactive, so a blocking ORM cannot index its entities); each Mutiny
 * [Uni] is bridged to the suspend port. The full [Product] round-trips through the JSONB `doc`
 * column via Jackson; identity/filter attributes are mirrored into scalar columns for querying.
 */
@ApplicationScoped
class PostgresProductRepository(
    private val sf: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
) : ProductRepository {

    override suspend fun findAll(): List<Product> = sf.withSession { s ->
        s.createQuery("FROM ProductEntity ORDER BY code", ProductEntity::class.java).resultList
    }.map { rows -> rows.map { it.toDomain() } }.coAwait()

    override suspend fun findById(id: UUID): Product? =
        sf.withSession { s -> s.find(ProductEntity::class.java, id) }
            .map { it?.toDomain() }
            .coAwait()

    override suspend fun findByCode(code: String): Product? = sf.withSession { s ->
        s.createQuery(
            "FROM ProductEntity WHERE code = :c OR legacyCode = :c",
            ProductEntity::class.java,
        ).setParameter("c", code).resultList
    }.map { rows -> rows.firstOrNull()?.toDomain() }.coAwait()

    override suspend fun save(product: Product, legacyCode: String?): Product =
        sf.withTransaction { s -> s.persist(newEntity(product, legacyCode)) }
            .replaceWith(product)
            .coAwait()

    override suspend fun update(product: Product): Product = sf.withTransaction { s ->
        s.find(ProductEntity::class.java, UUID.fromString(product.id)).flatMap { existing ->
            if (existing != null) {
                existing.applyFrom(product) // managed — flushes on commit; legacy_code preserved
                Uni.createFrom().item(product)
            } else {
                s.persist(newEntity(product, null)).replaceWith(product)
            }
        }
    }.coAwait()

    override suspend fun count(): Long =
        sf.withSession { s -> s.createQuery("SELECT COUNT(p) FROM ProductEntity p", Long::class.javaObjectType).singleResult }
            .coAwait()

    private fun newEntity(p: Product, legacyCode: String?): ProductEntity =
        ProductEntity().apply {
            legacyCode?.let { this.legacyCode = it }
            applyFrom(p)
        }

    private fun ProductEntity.applyFrom(p: Product) {
        id = UUID.fromString(p.id)
        code = p.code
        type = p.type
        status = p.status.name
        currency = p.currency
        doc = mapper.writeValueAsString(p)
    }

    private fun ProductEntity.toDomain(): Product = mapper.readValue(doc, Product::class.java)

    /** Bridge a Mutiny [Uni] to a coroutine without pulling in mutiny-kotlin. */
    private suspend fun <T> Uni<T>.coAwait(): T = subscribeAsCompletionStage().await()
}
