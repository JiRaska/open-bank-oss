// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.domain.ProductIds
import com.openbank.productcatalog.domain.ProductSeed
import io.quarkus.runtime.StartupEvent
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger

/**
 * Idempotent first-boot seed of the canonical catalogue (ADR-0105 P1). Flyway (V1) owns the schema;
 * the 15 canonical products come from [ProductSeed] (the single Kotlin source of truth) so the seed
 * and the domain never drift — each gets its durable canonical UUID via [ProductIds]. Seeds only an
 * empty table, so it is safe on every boot and never overwrites operator edits.
 *
 * Runs the reactive persist on a Vert.x context via [VertxContextSupport] (the supported way to drive
 * reactive Panache synchronously at startup), so it completes before the service serves traffic.
 */
@ApplicationScoped
class ProductCatalogSeeder(private val sf: Mutiny.SessionFactory, private val mapper: ObjectMapper) {
    private val log = Logger.getLogger(ProductCatalogSeeder::class.java)

    // The broad catch is deliberate: subscribeAndAwait wraps the reactive failure in an opaque
    // RuntimeException, so we inspect the cause chain (isUniqueViolation) and rethrow everything
    // that is NOT a lost seed race — a genuine DB fault must still fail the boot.
    @Suppress("TooGenericExceptionCaught")
    fun onStart(@Observes ev: StartupEvent) {
        val inserted = try {
            seed()
        } catch (e: RuntimeException) {
            // Concurrent first boot (e.g. KEDA scaling 0 → N replicas at once): the count==0 guard is
            // not atomic, so a second replica may also attempt the seed and lose the race on the UNIQUE
            // (code / legacy_code) constraints — its whole transaction rolls back. That is success, not
            // failure: the catalogue is seeded. Swallow only the unique-violation; rethrow anything else
            // so a genuine DB fault still fails the boot (a StartupEvent throwing crashloops the pod).
            if (isUniqueViolation(e)) {
                log.info("Catalogue already seeded by another replica (concurrent first boot) — skipping.")
                0
            } else {
                throw e
            }
        }
        if (inserted > 0) log.info("Seeded $inserted canonical products (ADR-0105 P1).")
    }

    private fun seed(): Int = VertxContextSupport.subscribeAndAwait {
        sf.withTransaction { s ->
            s.createQuery("SELECT COUNT(p) FROM ProductEntity p", Long::class.javaObjectType)
                .singleResult
                .flatMap { count ->
                    if (count > 0L) {
                        Uni.createFrom().item(0)
                    } else {
                        val entities = ProductSeed.products.map { p ->
                            val canonical = ProductIds.canonicalId(p.id)
                            ProductEntity().apply {
                                id = canonical
                                code = p.code
                                legacyCode = p.id
                                type = p.type
                                status = p.status.name
                                currency = p.currency
                                doc = mapper.writeValueAsString(p.copy(id = canonical.toString()))
                            }
                        }
                        entities.fold(Uni.createFrom().voidItem() as Uni<Void>) { acc, e ->
                            acc.flatMap { s.persist(e) }
                        }.replaceWith(entities.size)
                    }
                }
        }
    }

    /** True if [e] (or any cause) is a Postgres unique-violation (SQLState 23505) — a lost seed race. */
    private fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = cur.message.orEmpty()
            val byMessage = "23505" in msg || "duplicate key value" in msg
            if ((cur as? java.sql.SQLException)?.sqlState == "23505" ||
                cur is org.hibernate.exception.ConstraintViolationException ||
                byMessage
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }
}
