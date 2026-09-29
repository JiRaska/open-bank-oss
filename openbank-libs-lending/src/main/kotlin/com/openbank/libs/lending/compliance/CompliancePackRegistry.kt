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

    /**
     * Converges this registry onto the set of activations that are DURABLE — the rows a sibling
     * replica's approval leaves behind (#3467). Returns how many (key, version) pairs this call
     * added, so the caller can report a replica that was behind; 0 means already converged.
     *
     * The in-memory registry is per-pod, so [activate] alone makes a pack enforceable on exactly the
     * pod that served the approval. Without this, a sibling pod picked the pack up only when it next
     * restarted — an unbounded window in which two requests to the same service are judged against
     * different compliance rules, which is the four-eyes control diverging rather than a stale cache.
     *
     * ADDITIVE, not a replacement of the map, and that asymmetry is deliberate. A pack is never
     * un-activated — a version, once activated, is immutable history (ADR-0212 D3) — so on every real
     * state a union and a wholesale replace agree. They differ only on the race: a replace computed
     * from a snapshot read a moment before a concurrent [activate] would DROP that pack from the pod
     * that just approved it, refusing origination there until the next sync. A union cannot lose a
     * write, and re-applying the same rows is a no-op, which is what a repeated refresh does.
     *
     * Each proposal is re-validated exactly as [activate] validates it: a row that is not an approved
     * four-eyes decision does not become enforceable by arriving through this door instead.
     */
    fun syncFrom(proposals: List<Proposal<CompiledCompliancePack>>): Int {
        proposals.forEach(::requireApprovedFourEyes)
        var added = 0
        active.getAndUpdate { current ->
            added = 0
            var next = current
            proposals.forEach { proposal ->
                val compiled = proposal.action
                val versions = next[compiled.key].orEmpty()
                if (versions.none { it.pack.version == compiled.pack.version }) {
                    added++
                    next = next + (compiled.key to (versions + compiled).sortedBy { it.pack.version })
                }
            }
            next
        }
        return added
    }

    private fun requireApprovedFourEyes(proposal: Proposal<CompiledCompliancePack>) {
        if (proposal.state != ProposalState.APPROVED && proposal.state != ProposalState.EXECUTED) {
            fail("pack activation requires an APPROVED four-eyes proposal, got ${proposal.state}")
        }
        if (proposal.decidedBy == null || proposal.decidedBy == proposal.proposedBy) {
            fail("maker and checker must differ (four-eyes)")
        }
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
