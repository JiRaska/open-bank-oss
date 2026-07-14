// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence

import com.openbank.document.domain.DocumentTemplateSeed
import com.openbank.document.infrastructure.persistence.entity.DocumentTemplateEntity
import com.openbank.document.infrastructure.persistence.mapper.toEntity
import io.quarkus.runtime.StartupEvent
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger

/**
 * Idempotent first-boot (and every-boot) seed of the canonical demo templates (VOP, framework
 * agreement, current account agreement — each cs/en, one row per [DocumentTemplateSeed] entry)
 * from [DocumentTemplateSeed], mirroring `openbank-product-catalog`'s `ProductCatalogSeeder` in
 * mechanism. Adds only the [DocumentTemplateSeed] rows that are missing by fixed `id` — never
 * touches an existing row (an operator-authored template, or an earlier seed version whose fixed
 * id isn't in the current [DocumentTemplateSeed] list, is left untouched) — so a later
 * [DocumentTemplateSeed] update (a new fixed id + version, since published templates are
 * immutable) reaches an already-seeded environment on the next boot instead of only ever running
 * once against an empty table.
 *
 * Deliberately does NOT go through the coroutine (`suspend fun`) [com.openbank.document.application.port.out.TemplateRepositoryPort]
 * the rest of the service uses: a `runBlocking { repo.save(...) } ` call from a plain `@Observes
 * StartupEvent` method has no current Vert.x context, and `Panache.withSession`/`withTransaction`
 * require one (`SessionOperations.vertxContext()` throws `IllegalStateException: No current Vertx
 * context found`) — this crashed application boot entirely on first try (caught by this service's
 * own `CeremonyRepositoryImplIT`, an unrelated test, failing collaterally). [VertxContextSupport]
 * is the supported way to drive reactive Panache/Hibernate Reactive synchronously at startup — the
 * same mechanism `ProductCatalogSeeder` already uses — so this seeder talks to
 * [Mutiny.SessionFactory] and [DocumentTemplateEntity] directly instead.
 */
@ApplicationScoped
class DocumentTemplateSeeder(private val sf: Mutiny.SessionFactory) {
    private val log = Logger.getLogger(DocumentTemplateSeeder::class.java)

    // The broad catch is deliberate: a concurrent first boot (e.g. KEDA scaling 0 -> N replicas at
    // once) can race two replicas onto the same missing id, so one may lose the race on the fixed
    // seed `id` primary key — that is success, not failure. Rethrow anything that is NOT a lost
    // seed race so a genuine DB fault still fails the boot (a StartupEvent throwing crashloops the
    // pod).
    // UnusedParameter: `ev` is required by the CDI @Observes contract to declare the event type
    // even though the body never reads it — same as ProductCatalogSeeder.onStart's identical shape.
    @Suppress("TooGenericExceptionCaught", "UnusedParameter")
    fun onStart(@Observes ev: StartupEvent) {
        val inserted = try {
            seed()
        } catch (e: RuntimeException) {
            if (isUniqueViolation(e)) {
                log.info("Document templates already seeded by another replica (concurrent first boot) — skipping.")
                0
            } else {
                throw e
            }
        }
        if (inserted > 0) {
            log.info("Seeded $inserted canonical document templates (VOP, framework & account agreements, cs/en).")
        }
    }

    private fun seed(): Int = VertxContextSupport.subscribeAndAwait {
        sf.withTransaction { s ->
            DocumentTemplateSeed.templates.fold(Uni.createFrom().item(0)) { acc, template ->
                acc.flatMap { insertedSoFar ->
                    s.find(DocumentTemplateEntity::class.java, template.id).flatMap { existing ->
                        if (existing != null) {
                            Uni.createFrom().item(insertedSoFar)
                        } else {
                            s.persist(template.toEntity()).replaceWith(insertedSoFar + 1)
                        }
                    }
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
