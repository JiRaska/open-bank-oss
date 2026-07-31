// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.compliance

import com.openbank.lending.application.port.out.CompliancePackActivationRepository
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.lending.compliance.CompiledCompliancePack
import com.openbank.libs.lending.compliance.CompliancePackCompiler
import com.openbank.libs.lending.compliance.CompliancePackParser
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import com.openbank.libs.lending.compliance.PackProductType
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/** CDI wiring for the compliance-pack registry and its boot rehydration (ADR-0212 D4). */
@ApplicationScoped
class CompliancePackConfig {
    @Produces
    @ApplicationScoped
    fun compliancePackRegistry(): CompliancePackRegistry = CompliancePackRegistry()
}

/**
 * Rehydrates the in-memory [CompliancePackRegistry] from persisted activations at
 * boot: every APPROVED/EXECUTED row is re-parsed, re-compiled and re-activated as the
 * reconstructed four-eyes proposal it was approved as. A corrupt row fails the boot
 * loudly — silently starting without a jurisdiction's pack would let origination run
 * unprotected, which is the worse failure (ADR-0212 D2).
 */
@ApplicationScoped
class CompliancePackBootLoader(
    private val activations: CompliancePackActivationRepository,
    private val registry: CompliancePackRegistry,
    private val sessionFactory: Mutiny.SessionFactory,
) {
    private val log = Logger.getLogger(CompliancePackBootLoader::class.java)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun onStart(@Observes @Suppress("UnusedParameter") event: StartupEvent) {
        val rows = io.quarkus.vertx.VertxContextSupport.subscribeAndAwait {
            sessionFactory.withSession { activations.findActivated() }
        }
        rows.forEach { row ->
            val compiled = CompliancePackCompiler.compile(CompliancePackParser.fromJson(row.payload))
            registry.activate(
                Proposal(
                    id = "boot-${row.id}",
                    action = compiled,
                    proposedBy = row.proposedBy,
                    proposedAt = row.proposedAt.toInstant(),
                    state = ProposalState.EXECUTED,
                    decidedBy = row.decidedBy ?: "boot:unknown-checker",
                    decidedAt = row.decidedAt?.toInstant(),
                    decisionReason = row.decisionReason,
                ),
            )
        }
        if (rows.isNotEmpty()) {
            log.infof("compliance packs rehydrated: %d activation(s)", rows.size)
        }
    }
}

/**
 * Fail-closed origination guard (ADR-0212 D2). Behind the bootstrap flag
 * `lending.compliance.enforce-pack` (restart-required, default false — ADR-0212 D4
 * bootstrap: flip only after the CZ reference pack is seeded and four-eyes-activated).
 * When on, every origination request must name a (jurisdiction, productType) pair
 * with an active pack, or be refused.
 */
@ApplicationScoped
class CompliancePackGuard(
    private val registry: CompliancePackRegistry,
    private val clock: Clock,
    @param:ConfigProperty(name = "lending.compliance.enforce-pack", defaultValue = "false")
    val enforced: Boolean,
) {
    fun checkOriginationAllowed(jurisdiction: String?, productType: String?) {
        if (!enforced) return
        require(!jurisdiction.isNullOrBlank()) {
            "jurisdiction is required (compliance pack enforcement is on, ADR-0212 D2)"
        }
        val type = PackProductType.entries.firstOrNull { it.name == productType }
            ?: throw IllegalArgumentException("unknown productType '$productType'")
        registry.activePack(jurisdiction, type, LocalDate.now(clock))
            ?: throw IllegalArgumentException(
                "no active compliance pack for $jurisdiction/$type — origination refused (ADR-0212 D2)",
            )
    }

    /** The active pack for the pair, or null — no enforcement, plain lookup (pinning, mandatory steps). */
    fun resolveOriginationPack(jurisdiction: String?, productType: String?): CompiledCompliancePack? {
        val type = PackProductType.entries.firstOrNull { it.name == productType } ?: return null
        return jurisdiction?.let { registry.activePack(it, type, LocalDate.now(clock)) }
    }
}
