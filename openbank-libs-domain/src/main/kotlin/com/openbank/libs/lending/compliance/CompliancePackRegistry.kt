// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/** Four-eyes or registry invariant violated — the activation/lookup is refused. */
class PackActivationException(message: String) : IllegalStateException(message)

/**
 * In-memory registry of compiled compliance packs (ADR-0212 D4, ADR-0218 D3).
 * Activation is runtime four-eyes: the only way in is an APPROVED
 * [Proposal] whose maker and checker differ (the segregation-of-duties rule is
 * enforced by the MakerChecker state machine on the way in, and re-asserted here).
 * No Flyway migration, no service release per pack.
 *
 * The registry is an [AtomicReference] over an immutable map: activation swaps one
 * reference, readers never block, and a lookup resolves the pack version effective
 * at the requested date — in-flight applications keep their pinned version
 * (ADR-0212 D3), a new version never rewrites history.
 */
class CompliancePackRegistry {

    private val active: AtomicReference<Map<PackKey, List<CompiledCompliancePack>>> =
        AtomicReference(emptyMap())

    /**
     * Activates the pack carried by an APPROVED four-eyes [proposal]. Rejects:
     * a non-approved proposal, maker == checker (belt-and-braces behind MakerChecker),
     * and re-activation of the exact same (key, version) — a version, once activated,
     * is immutable history.
     */
    fun activate(proposal: Proposal<CompiledCompliancePack>): CompiledCompliancePack {
        if (proposal.state != ProposalState.APPROVED && proposal.state != ProposalState.EXECUTED) {
            fail("pack activation requires an APPROVED four-eyes proposal, got ${proposal.state}")
        }
        if (proposal.decidedBy == null || proposal.decidedBy == proposal.proposedBy) {
            fail("maker and checker must differ (four-eyes)")
        }
        val compiled = proposal.action
        val key = compiled.key
        active.getAndUpdate { current ->
            val versions = current[key].orEmpty()
            if (versions.any { it.pack.version == compiled.pack.version }) {
                fail("pack ${key.jurisdiction}/${key.productType} v${compiled.pack.version} is already activated")
            }
            current + (key to (versions + compiled).sortedBy { it.pack.version })
        }
        return compiled
    }

    private fun fail(message: String): Nothing = throw PackActivationException(message)

    /**
     * The pack version effective at [asOf] for (jurisdiction, productType), or null —
     * fail-closed: the service rejects origination when this returns null
     * (ADR-0212 D2: no active pack, no origination).
     */
    fun activePack(key: PackKey, asOf: LocalDate): CompiledCompliancePack? = active.get()[key].orEmpty()
        .filter { it.pack.isEffectiveOn(asOf) }
        .maxByOrNull { it.pack.version }

    /** Convenience overload for call sites that have the raw pair. */
    fun activePack(jurisdiction: String, productType: PackProductType, asOf: LocalDate): CompiledCompliancePack? =
        activePack(PackKey(jurisdiction, productType), asOf)

    /** All packs effective at [asOf] — for the admin surface and bootstrap checks. */
    fun allActive(asOf: LocalDate): List<CompiledCompliancePack> =
        active.get().values.flatten().filter { it.pack.isEffectiveOn(asOf) }
}
