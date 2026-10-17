// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import com.openbank.libs.storage.ObjectStoreBlobEntity
import com.openbank.libs.storage.PostgresBlobStore
import io.quarkus.arc.Arc
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Duration
import java.time.Instant

/**
 * Reads at most ONE row of every mapped entity, so Hibernate Reactive's first-use costs — session
 * open, per-entity SQL rendering, result-set hydration, and the entity classes' own loading — are
 * paid before readiness rather than by a customer (#11890 follow-up; on lending-service the gap
 * between the two `SELECT`s of the first `loan-book` call was ~300 ms after `select 1` had already
 * warmed the pool).
 *
 * Read-only by construction (`select ... fetch first 1 rows only`, no ordering, no lock). Every
 * entity is its own attempt, so a missing table or an unmappable type costs one entity, not the
 * step. Bounded by [deadline]: entities not reached in time are counted as skipped.
 *
 * Entities that belong to an OPTIONAL adapter are skipped when that adapter's bean is not in the
 * container ([shouldWarm]). `ObjectStoreBlobEntity` is `@Entity`-scanned into EVERY service that has
 * `openbank-libs-runtime`, but its table (`object_store_blobs`) exists only in a service that opted into
 * `openbank.objectstore.backend=postgres`; reading it anywhere else made each pod issue a query that
 * Postgres answered `relation "object_store_blobs" does not exist (42P01)` on every boot -- swallowed
 * here, but written to the database log (found on the sandbox swift-service DB, 2026-10-17).
 *
 * Only referenced after [ENTRY_CLASS] resolved on the classpath — services without Hibernate
 * Reactive must never link this class. `VertxContextSupport.subscribeAndAwait` supplies the
 * duplicated Vert.x context Hibernate Reactive requires; that is safe here because the caller
 * is the warm-up's own daemon thread, never an event loop.
 */
internal object HibernateReactiveWarmup {
    const val ENTRY_CLASS = "org.hibernate.reactive.mutiny.Mutiny\$SessionFactory"

    /**
     * False for an entity whose backing adapter is absent. [adapterActive] answers "is the bean that
     * owns this entity's table present?" and is only asked for entities that have such an owner.
     */
    fun shouldWarm(type: Class<*>, adapterActive: (Class<*>) -> Boolean): Boolean =
        OPTIONAL_ENTITY_OWNERS[type]?.let(adapterActive) ?: true

    /** entity class -> the bean class whose presence means the entity's table exists. */
    private val OPTIONAL_ENTITY_OWNERS: Map<Class<*>, Class<*>> = mapOf(
        ObjectStoreBlobEntity::class.java to PostgresBlobStore::class.java,
    )

    fun warm(deadline: Instant, perEntity: Duration): String {
        val handle = Arc.container().instance(Mutiny.SessionFactory::class.java)
        if (!handle.isAvailable) return "no Mutiny.SessionFactory bean"
        val factory = handle.get()
        val all = factory.metamodel.entities.mapNotNull { e -> e.javaType?.let { e.name to it } }
        val entities = all.filter { (_, type) ->
            shouldWarm(type) { owner -> Arc.container().beanManager().getBeans(owner).isNotEmpty() }
        }
        val inactive = all.size - entities.size
        var ok = 0
        var failed = 0
        var skipped = 0
        for ((name, type) in entities) {
            if (Instant.now().isAfter(deadline)) {
                skipped++
                continue
            }
            val read: () -> Uni<*> = {
                factory.withSession { s ->
                    s.createSelectionQuery("from $name", type).setMaxResults(1).resultList
                }
            }
            try {
                VertxContextSupport.subscribeAndAwait { read().ifNoItem().after(perEntity).fail() }
                ok++
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Throwable) {
                failed++
            }
        }
        return "${entities.size} entities: $ok read, $failed failed, $skipped skipped (deadline)" +
            if (inactive > 0) ", $inactive not warmed (optional adapter inactive)" else ""
    }
}
