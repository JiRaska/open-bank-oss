// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.productcatalog.domain.Product
import com.openbank.productcatalog.domain.ProductIds
import com.openbank.productcatalog.domain.ProductSeed
import io.quarkus.runtime.StartupEvent
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger
import java.util.UUID

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
class ProductCatalogSeeder(
    private val sf: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.catalog.bank-v1-compatibility-enabled", defaultValue = "true")
    private val bankCompatibilityEnabled: Boolean,
) {
    private val log = Logger.getLogger(ProductCatalogSeeder::class.java)

    // The broad catch is deliberate: subscribeAndAwait wraps the reactive failure in an opaque
    // RuntimeException, so we inspect the cause chain (isUniqueViolation) and rethrow everything
    // that is NOT a lost seed race — a genuine DB fault must still fail the boot.
    @Suppress("TooGenericExceptionCaught", "UnusedParameter")
    fun onStart(@Observes @Priority(Interceptor.Priority.APPLICATION) ev: StartupEvent) {
        if (!bankCompatibilityEnabled) return
        val inserted = try {
            seed()
        } catch (e: RuntimeException) {
            // Concurrent first boot (e.g. KEDA scaling 0 → N replicas at once): the count==0 guard is
            // not atomic, so a second replica may also attempt the seed and lose the race on the UNIQUE
            // (code / legacy_code) constraints — its whole transaction rolls back. That is success, not
            // failure: the catalogue is seeded. Swallow only the unique-violation; rethrow anything else
            // so a genuine DB fault still fails the boot (a StartupEvent throwing crashloops the pod).
            if (PostgresConflicts.isUniqueViolation(e)) {
                log.info("Catalogue already seeded by another replica (concurrent first boot) — skipping.")
                0
            } else {
                throw e
            }
        }
        if (inserted > 0) log.info("Seeded $inserted canonical products (ADR-0105 P1).")
        // Heal rows seeded before a fee schedule was added to ProductSeed (the fee-schedule addition
        // never reached an already-seeded catalogue, since seed() only runs on an empty table).
        val patched = try {
            backfillFees()
        } catch (e: RuntimeException) {
            if (PostgresConflicts.isUniqueViolation(e)) 0 else throw e
        }
        if (patched > 0) log.info("Backfilled fee schedules for $patched catalogue product(s) from the seed.")
    }

    /**
     * Heals stale catalogue rows that predate a fee-schedule addition to [ProductSeed]: for each seed
     * product that exists in the DB but whose persisted doc has an empty `fees` array, backfill the
     * fees from the seed. Deliberately narrow — it ONLY touches products with no fees, so an operator
     * who curated a product's fees (via the PUT endpoint) is never overwritten, keeping the seeder's
     * "never clobber operator edits" contract while leaving [ProductSeed] the single source of truth.
     */
    private fun backfillFees(): Int = VertxContextSupport.subscribeAndAwait {
        sf.withTransaction { s ->
            val seedById = ProductSeed.products
                .filter { it.fees.isNotEmpty() }
                .associateBy { ProductIds.canonicalId(it.id) }
            s.createQuery("FROM ProductEntity", ProductEntity::class.java).resultList.flatMap { entities ->
                val patched = entities.count { e -> backfillFeesFor(e, seedById) }
                log.info(
                    "Fee backfill: scanned ${entities.size} products, ${seedById.size} seed products have fees, patched $patched.",
                )
                // Explicit flush so the dirty managed entities are unambiguously written before commit.
                if (patched > 0) s.flush().replaceWith(patched) else Uni.createFrom().item(patched)
            }
        }
    } ?: 0

    /**
     * Backfill [e]'s doc fees from the seed if — and only if — the seed has fees for it and its
     * persisted doc has none. Returns true iff it patched (a managed entity, flushed by the caller).
     */
    private fun backfillFeesFor(e: ProductEntity, seedById: Map<UUID, Product>): Boolean {
        val seedP = seedById[e.id] ?: return false
        val doc = mapper.readTree(e.doc) as? ObjectNode ?: return false
        val fees = doc.get("fees")
        if (fees != null && fees.isArray && fees.size() > 0) return false // already has fees — leave it
        doc.set<JsonNode>("fees", mapper.valueToTree(seedP.fees))
        e.doc = mapper.writeValueAsString(doc)
        return true
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
}
