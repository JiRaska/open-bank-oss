// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.openbank.kyb.application.port.out.BeneficialOwnershipPort
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.application.port.out.UboAdapter
import com.openbank.kyb.domain.model.CountryPack
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Picks the beneficial-ownership source for an identifier, mirroring [RegistryRouter]: a
 * register-backed adapter first, the self-declaration fallback last.
 *
 * The one behaviour worth reading twice is what happens when a register-backed adapter throws.
 * The finding degrades to [UboSource.UNAVAILABLE] — NOT to SELF_DECLARATION — because the two
 * demand different work. "The UK register was down" is fixed by retrying; "this jurisdiction has no
 * queryable register" is fixed by asking the customer. Merging them produces a case queue where a
 * transient outage looks like a customer who owes us a declaration, and nobody retries.
 */
@ApplicationScoped
class UboRouter : BeneficialOwnershipPort {

    @Inject lateinit var adapters: Instance<UboAdapter>

    @Inject lateinit var packs: CountryPackRegistry

    @Inject lateinit var metrics: KybMetricsPort

    @Inject lateinit var clock: Clock

    private val log = Logger.getLogger(UboRouter::class.java)

    override suspend fun lookup(identifier: LegalEntityIdentifier): UboFinding {
        val now = Instant.now(clock)
        val pack = packs.packFor(identifier.scheme.country, LocalDate.ofInstant(now, ZoneOffset.UTC))
            ?: packs.packForScheme(identifier.scheme, LocalDate.ofInstant(now, ZoneOffset.UTC))
            ?: return unavailable(identifier, now, reason = "no country pack")
        val ordered = adapters.sortedBy { if (it is SelfDeclarationUboAdapter) 1 else 0 }
        val adapter = ordered.firstOrNull { it.supports(identifier.scheme) }
            ?: return unavailable(identifier, now, reason = "no adapter", pack = pack)
        return try {
            val finding = adapter.lookup(identifier, pack)
                ?: return unavailable(identifier, now, reason = "adapter declined", pack = pack)
            metrics.registryLookup(adapter.source, if (finding.owners.isEmpty()) "empty" else "found")
            finding
        } catch (e: RegistryUnavailableException) {
            // Deliberately not rethrown: an unreachable UBO register must not fail the whole
            // onboarding lookup. It downgrades the finding, and the case's evidence requirement is
            // what stops it proceeding — a decision that belongs to the case, not to the router.
            log.warnf(e, "UBO register %s unavailable for %s", adapter.source, identifier.scheme)
            metrics.registryLookup(adapter.source, "unavailable")
            unavailable(identifier, now, reason = "register unavailable", pack = pack)
        }
    }

    private fun unavailable(
        identifier: LegalEntityIdentifier,
        now: Instant,
        reason: String,
        pack: CountryPack? = null,
    ): UboFinding {
        log.debugf("UBO unavailable for %s: %s", identifier.scheme, reason)
        return UboFinding(
            identifier = identifier,
            source = UboSource.UNAVAILABLE,
            owners = emptyList(),
            registerStatements = emptyList(),
            threshold = pack?.uboRegister?.threshold ?: DEFAULT_THRESHOLD,
            registerName = pack?.uboRegister?.name,
            sourceRef = null,
            fetchedAt = now,
        )
    }

    private companion object {
        /** AMLD5 Art. 3(6): 25% is the fallback when no pack states one. */
        const val DEFAULT_THRESHOLD = 0.25
    }
}
