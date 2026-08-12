// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.StartupEvent
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger

/** Idempotently expands every persisted v1 product into its canonical v2 banking mapping. */
@ApplicationScoped
class BankV1CompatibilityBackfill(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    private val projector: BankV1CompatibilityProjector,
) {
    private val log = Logger.getLogger(BankV1CompatibilityBackfill::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + STARTUP_PRIORITY_OFFSET) event: StartupEvent) {
        val mapped = run()
        if (mapped > 0) log.info("Mapped $mapped legacy banking product(s) into the v2 catalog.")
    }

    fun run(): Int = VertxContextSupport.subscribeAndAwait {
        sessions.withTransaction { session ->
            session.createQuery(
                "FROM ProductEntity ORDER BY code",
                ProductEntity::class.java,
            ).resultList.flatMap { products ->
                products.fold(Uni.createFrom().item(0)) { result, entity ->
                    result.flatMap { count ->
                        session.find(BankV1ProductMappingEntity::class.java, entity.id).flatMap { existing ->
                            if (existing != null) {
                                Uni.createFrom().item(count)
                            } else {
                                val product = mapper.readValue(
                                    entity.doc,
                                    com.openbank.productcatalog.domain.Product::class.java,
                                )
                                    .copy(id = entity.id.toString(), revision = entity.revision)
                                projector.ensureMapped(
                                    session,
                                    product,
                                    entity.legacyCode,
                                    "system:bank-v1-backfill",
                                ).replaceWith(count + 1)
                            }
                        }
                    }
                }
            }
        }
    } ?: 0

    private companion object {
        const val STARTUP_PRIORITY_OFFSET = 100
    }
}
