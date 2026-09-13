// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.application.port.out.BusinessRegistryPort
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.RegistryAdapter
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RegistrySearchQuery
import com.openbank.kyb.domain.model.RegistrySearchResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Picks the register for a scheme (ADR-0284 D1). Order matters only where two adapters claim one
 * scheme; the manual-attestation adapter claims everything and is consulted LAST, so a scheme with
 * a real register never falls through to self-declaration while that register is merely down —
 * an outage propagates as [RegistryUnavailableException] and the case goes to review, which is
 * the honest state.
 */
@ApplicationScoped
class RegistryRouter : BusinessRegistryPort {

    @Inject lateinit var adapters: Instance<RegistryAdapter>

    @Inject lateinit var metrics: KybMetricsPort

    /**
     * Routed like [lookup] except for the fallback: the manual-attestation adapter cannot search,
     * so a scheme whose only adapter is that one answers **null** — "this register has no search" —
     * rather than an empty list. The caller hides the search box on null; an empty list would tell
     * the customer their company does not exist.
     */
    override suspend fun search(scheme: IdentifierScheme, query: RegistrySearchQuery): RegistrySearchResult? {
        val adapter = adapters
            .sortedBy { if (it is ManualAttestationRegistryAdapter) 1 else 0 }
            .firstOrNull { it.supports(scheme) } ?: return null
        return try {
            adapter.search(scheme, query)?.also { metrics.registryLookup(adapter.source, "search") }
        } catch (e: RegistryUnavailableException) {
            // A search outage must not read as "no such company": the customer would go on to type
            // an identifier that is also about to fail, or conclude the bank does not know them.
            metrics.registryLookup(adapter.source, "search_unavailable")
            throw e
        }
    }

    override suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract? {
        val ordered = adapters.sortedBy { if (it is ManualAttestationRegistryAdapter) 1 else 0 }
        val adapter = ordered.firstOrNull { it.supports(identifier.scheme) }
            ?: throw RegistryUnavailableException("none for ${identifier.scheme}")
        return try {
            adapter.lookup(identifier, declared).also {
                metrics.registryLookup(adapter.source, if (it == null) "not_found" else "found")
            }
        } catch (e: RegistryUnavailableException) {
            metrics.registryLookup(adapter.source, "unavailable")
            throw e
        }
    }
}
