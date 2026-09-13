// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.productcatalog.application.CatalogConflictException
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger
import java.time.Duration

/** Idempotently expands every persisted v1 product into its canonical v2 banking mapping. */
@ApplicationScoped
class BankV1CompatibilityBackfill(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    private val projector: BankV1CompatibilityProjector,
    domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.catalog.bank-v1-compatibility-enabled", defaultValue = "true")
    private val bankCompatibilityEnabled: Boolean,
) {
    private val log = Logger.getLogger(BankV1CompatibilityBackfill::class.java)
    private val liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)

    @Suppress("UnusedParameter")
    fun onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + STARTUP_PRIORITY_OFFSET) event: StartupEvent) {
        if (!bankCompatibilityEnabled) return
        val mapped = runLenient()
        liveness.recordSuccess()
        if (mapped > 0) log.info("Mapped $mapped legacy banking product(s) into the v2 catalog.")
    }

    fun run(): Int {
        if (!bankCompatibilityEnabled) return 0
        return VertxContextSupport.subscribeAndAwait { reconcile(failOnConflict = true) }?.changed ?: 0
    }

    /**
     * A compatibility conflict is fail-closed for that product, never for the whole catalog.
     * Operators can resolve it through the catalog evidence trail while unrelated products stay
     * available during a rollout.
     */
    internal fun runLenient(): Int {
        val result = VertxContextSupport.subscribeAndAwait {
            reconcile(failOnConflict = false)
        } ?: ReconciliationResult()
        logConflicts(result)
        return result.changed
    }

    /**
     * Closes the surge-rollout tail: an old pod may commit after every new pod completed startup.
     * The suspend signature gives the real scheduler a Vert.x context for reactive persistence.
     */
    @Scheduled(
        every = "\${openbank.catalog.bank-v1-reconcile-interval:30s}",
        delayed = "\${openbank.catalog.bank-v1-reconcile-initial-delay:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "bank-v1-compatibility-reconciler",
    )
    suspend fun reconcileAfterRollingWriters() {
        if (!bankCompatibilityEnabled) {
            liveness.recordSuccess()
            return
        }
        val result = reconcile(failOnConflict = false).awaitSuspending()
        if (result.changed > 0) {
            log.info("Reconciled ${result.changed} banking product(s) after a mixed-version write.")
        }
        logConflicts(result)
        liveness.recordSuccess()
    }

    private fun logConflicts(result: ReconciliationResult) {
        result.conflicts.forEach { log.error("Banking compatibility reconciliation conflict: ${it.message}") }
    }

    private fun reconcile(failOnConflict: Boolean): Uni<ReconciliationResult> = sessions.withSession { session ->
        session.createQuery(
            "FROM ProductEntity ORDER BY code",
            ProductEntity::class.java,
        ).resultList
    }.flatMap { products ->
        products.fold(Uni.createFrom().item(ReconciliationResult())) { accumulated, detached ->
            accumulated.flatMap { result ->
                sessions.withTransaction { session ->
                    session.find(ProductEntity::class.java, detached.id).flatMap { entity ->
                        checkNotNull(entity) { "legacy product ${detached.id} disappeared during reconciliation" }
                        val product = LegacyProductJson.readProduct(
                            mapper,
                            entity.doc,
                        ).copy(id = entity.id.toString(), revision = entity.revision)
                        projector.ensureMapped(
                            session,
                            product,
                            entity.legacyCode,
                            "system:bank-v1-backfill",
                        )
                    }
                }.map { changed -> result.copy(changed = result.changed + if (changed) 1 else 0) }
                    .onFailure(CatalogConflictException::class.java)
                    .recoverWithItem { conflict ->
                        result.copy(conflicts = result.conflicts + conflict as CatalogConflictException)
                    }
            }
        }.map { result ->
            if (failOnConflict && result.conflicts.isNotEmpty()) throw result.conflicts.first()
            result
        }
    }

    private data class ReconciliationResult(
        val changed: Int = 0,
        val conflicts: List<CatalogConflictException> = emptyList(),
    )

    private companion object {
        const val STARTUP_PRIORITY_OFFSET = 100
        const val WORKFLOW_NAME = "bank-v1-compatibility-reconciliation"
        val EXPECTED_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
