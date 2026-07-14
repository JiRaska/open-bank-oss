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
 * Idempotent first-boot seed of the three canonical demo templates (VOP, framework agreement,
 * current account agreement — each cs/en) from [DocumentTemplateSeed], mirroring
 * `openbank-product-catalog`'s `ProductCatalogSeeder` byte for byte in mechanism. Seeds only an
 * empty `document_templates` table, so it is safe on every boot and never overwrites an
 * operator-authored template.
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
    // once) is not fully excluded by the count==0 guard below, so a second replica may lose the
    // race on the (code, version) unique constraint (or the fixed seed `id` primary key) — that is
    // success, not failure. Rethrow anything that is NOT a lost seed race so a genuine DB fault
    // still fails the boot (a StartupEvent throwing crashloops the pod).
    @Suppress("TooGenericExceptionCaught")
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
        if (inserted > 0) log.info("Seeded $inserted canonical document templates (VOP, framework & account agreements, cs/en).")
    }

    private fun seed(): Int =
        VertxContextSupport.subscribeAndAwait {
            sf.withTransaction { s ->
                s.createQuery("SELECT COUNT(t) FROM DocumentTemplateEntity t", Long::class.javaObjectType)
                    .singleResult
                    .flatMap { count ->
                        if (count > 0L) {
                            Uni.createFrom().item(0)
                        } else {
                            val entities = DocumentTemplateSeed.templates.map { it.toEntity() }
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
