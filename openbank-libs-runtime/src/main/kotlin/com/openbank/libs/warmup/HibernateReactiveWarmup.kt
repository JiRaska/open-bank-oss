// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

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
 * Only referenced after [ENTRY_CLASS] resolved on the classpath — services without Hibernate
 * Reactive must never link this class. `VertxContextSupport.subscribeAndAwait` supplies the
 * duplicated Vert.x context Hibernate Reactive requires; that is safe here because the caller
 * is the warm-up's own daemon thread, never an event loop.
 */
internal object HibernateReactiveWarmup {
    const val ENTRY_CLASS = "org.hibernate.reactive.mutiny.Mutiny\$SessionFactory"

    fun warm(deadline: Instant, perEntity: Duration): String {
        val handle = Arc.container().instance(Mutiny.SessionFactory::class.java)
        if (!handle.isAvailable) return "no Mutiny.SessionFactory bean"
        val factory = handle.get()
        val entities = factory.metamodel.entities.mapNotNull { e -> e.javaType?.let { e.name to it } }
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
        return "${entities.size} entities: $ok read, $failed failed, $skipped skipped (deadline)"
    }
}
