// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.application.DuplicateProductCodeException
import com.openbank.productcatalog.application.ProductUpdateConflictException
import com.openbank.productcatalog.application.port.out.ProductRepository
import com.openbank.productcatalog.domain.Product
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
    private val compatibilityProjector: BankV1CompatibilityProjector,
) : ProductRepository {

    override suspend fun findAll(): List<Product> = sf.withSession { s ->
        s.createQuery("FROM ProductEntity ORDER BY code", ProductEntity::class.java).resultList
    }.map { rows -> rows.map { it.toDomain() } }.awaitSuspending()

    override suspend fun findById(id: UUID): Product? = sf.withSession { s -> s.find(ProductEntity::class.java, id) }
        .map { it?.toDomain() }
        .awaitSuspending()

    override suspend fun findByCode(code: String): Product? = sf.withSession { s ->
        s.createQuery(
            "FROM ProductEntity WHERE code = :c OR legacyCode = :c",
            ProductEntity::class.java,
        ).setParameter("c", code).resultList
    }.map { rows -> rows.firstOrNull()?.toDomain() }.awaitSuspending()

    // Hibernate Reactive may wrap a driver constraint in RuntimeException; classify its cause chain.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun save(product: Product, legacyCode: String?, actorId: String): Product = try {
        sf.withTransaction { s ->
            s.persist(newEntity(product, legacyCode))
                .flatMap { s.flush() }
                .flatMap { compatibilityProjector.ensureMapped(s, product, legacyCode, actorId) }
        }
            .replaceWith(product)
            .awaitSuspending()
    } catch (e: RuntimeException) {
        if (PostgresConflicts.isUniqueViolation(e)) {
            throw DuplicateProductCodeException("Product with code '${product.code}' already exists")
        }
        throw e
    }

    override suspend fun update(product: Product, actorId: String): Product = try {
        sf.withTransaction { s ->
            s.createQuery(
                "FROM ProductEntity WHERE id = :id AND revision = :revision",
                ProductEntity::class.java,
            )
                .setParameter("id", UUID.fromString(product.id))
                .setParameter("revision", product.revision)
                .resultList
                .map { it.firstOrNull() }
                .flatMap { existing ->
                    if (existing != null) {
                        val previous = existing.toDomain()
                        val updated = product.copy(revision = product.revision + 1)
                        existing.applyFrom(updated) // managed — flushes on commit; legacy_code preserved
                        compatibilityProjector.syncDraft(s, previous, updated, actorId).replaceWith(updated)
                    } else {
                        throw ProductUpdateConflictException(
                            "Product ${product.id} was modified concurrently (expected revision ${product.revision})",
                        )
                    }
                }
        }.awaitSuspending()
    } catch (e: jakarta.persistence.OptimisticLockException) {
        throw ProductUpdateConflictException("Product ${product.id} was modified concurrently", e)
    } catch (e: org.hibernate.StaleObjectStateException) {
        throw ProductUpdateConflictException("Product ${product.id} was modified concurrently", e)
    }

    override suspend fun count(): Long = sf.withSession { s ->
        s.createQuery("SELECT COUNT(p) FROM ProductEntity p", Long::class.javaObjectType).singleResult
    }
        .awaitSuspending()

    private fun newEntity(p: Product, legacyCode: String?): ProductEntity = ProductEntity().apply {
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

    // `doc` retains the pre-ADR-0105 seed alias (for example `prod-003`) so a database
    // upgrade does not need to rewrite JSONB. The relational primary key is the canonical
    // product identity, however, and it is the only id the v1 API may expose: downstream
    // account records hold that UUID and must be able to round-trip it through this resource.
    private fun ProductEntity.toDomain(): Product = mapper.readValue(doc, Product::class.java).copy(
        id = id.toString(),
        revision = revision,
    )
}
