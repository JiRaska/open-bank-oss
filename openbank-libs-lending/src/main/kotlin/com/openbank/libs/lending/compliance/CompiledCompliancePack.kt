// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import com.openbank.libs.lending.origination.OriginationState
import java.math.BigDecimal
import java.security.MessageDigest

/** Compile-time pack validation failed — activation is refused (fail-closed). */
class CompliancePackValidationException(message: String) : IllegalArgumentException(message)

/** The activation-ready, immutable form of a [CompliancePack] (ADR-0218 D3
 * compile-at-activation): validated once, then served from memory for the pack's
 * whole lifetime — origination never re-parses or re-validates per request.
 * [contentHash] pins the exact canonical content into the evidence chain
 * (ADR-0214): what the contract was originated under is provable, not asserted.
 */
data class CompiledCompliancePack(
    val pack: CompliancePack,
    val contentHash: String,
    val disclosureById: Map<String, PackDisclosure>,
) {
    val key: PackKey get() = PackKey(pack.jurisdiction, pack.productType)
}

/** Lookup key packs are activated and resolved under. */
data class PackKey(val jurisdiction: String, val productType: PackProductType)

/**
 * Validates a parsed pack and compiles it into its immutable runtime form. Every
 * rule here is fail-closed: a pack that violates one is never activated, so an
 * unlawful configuration cannot reach origination even with a signed four-eyes
 * approval — the human gate and the machine gate are independent (ADR-0212 D2/D4).
 */
object CompliancePackCompiler {

    private const val MAX_DPD_THRESHOLD = 180

    /** Origination states a pack may mark mandatory — the graph's optional states only. */
    private val PARAMETERISABLE_STEPS: Set<OriginationState> = setOf(
        OriginationState.DOCS_REQUIRED,
        OriginationState.REFLECTION_PERIOD,
    )

    fun compile(pack: CompliancePack): CompiledCompliancePack {
        validateShape(pack)
        validateDisclosures(pack)
        validateMandatoryChecks(pack)
        return CompiledCompliancePack(
            pack = pack,
            contentHash = contentHash(pack),
            disclosureById = pack.disclosures.associateBy { it.id },
        )
    }

    private fun validateShape(pack: CompliancePack) {
        if (pack.jurisdiction.isBlank()) fail("jurisdiction must not be blank")
        if (pack.version < 1) fail("version must be >= 1")
        if (pack.effectiveTo != null && !pack.effectiveTo.isAfter(pack.effectiveFrom)) {
            fail("effectiveTo must be after effectiveFrom")
        }
        val badSteps = pack.requiredSteps - PARAMETERISABLE_STEPS
        if (badSteps.isNotEmpty()) {
            fail("requiredSteps may only contain optional states $PARAMETERISABLE_STEPS, got $badSteps")
        }
        if (pack.requiredSteps.contains(OriginationState.REFLECTION_PERIOD) && pack.reflectionPeriodDays == null) {
            fail("REFLECTION_PERIOD is mandatory but reflectionPeriodDays is not set")
        }
        if (pack.coolingOffDays < 0) fail("coolingOffDays must be >= 0")
        validateFinancials(pack)
    }

    private fun validateFinancials(pack: CompliancePack) {
        pack.earlyRepaymentCompensationCap?.let { cap ->
            if (cap < BigDecimal.ZERO || cap > BigDecimal.ONE) {
                fail("earlyRepaymentCompensationCap must be in [0, 1]")
            }
        }
        if (pack.terminationRules.noticePeriodDays < 0) {
            fail("terminationRules.noticePeriodDays must be >= 0")
        }
        if (pack.terminationRules.defaultDpdThreshold !in 1..MAX_DPD_THRESHOLD) {
            fail("terminationRules.defaultDpdThreshold must be in 1..$MAX_DPD_THRESHOLD (CRR Art. 178 bounds)")
        }
    }

    private fun validateDisclosures(pack: CompliancePack) {
        if (pack.disclosures.map { it.id }.toSet().size != pack.disclosures.size) {
            fail("duplicate disclosure id")
        }
        pack.disclosures.forEach { disclosure ->
            if (disclosure.templateKey.isBlank()) {
                fail("disclosure '${disclosure.id}': templateKey must not be blank")
            }
            if (disclosure.languages.isEmpty()) {
                fail("disclosure '${disclosure.id}': at least one language required")
            }
        }
    }

    private fun validateMandatoryChecks(pack: CompliancePack) {
        val ids = pack.mandatoryChecks.map { it.id }
        if (ids.toSet().size != ids.size) fail("duplicate mandatoryChecks rule id")
    }

    private fun fail(message: String): Nothing = throw CompliancePackValidationException(message)

    /** Canonical content rendering → SHA-256; stable across JVMs and map orderings. */
    private fun contentHash(pack: CompliancePack): String {
        val canonical = buildString {
            append(pack.jurisdiction).append('|')
            append(pack.productType.name).append('|')
            append(pack.version).append('|')
            append(pack.effectiveFrom).append('|')
            append(pack.effectiveTo ?: "-").append('|')
            append(pack.requiredSteps.map { it.name }.sorted().joinToString(",")).append('|')
            append(pack.coolingOffDays).append('|')
            append(pack.reflectionPeriodDays ?: "-").append('|')
            append(pack.aprDisclosure.label).append('|').append(pack.aprDisclosure.locale).append('|')
            append(pack.earlyRepaymentCompensationCap?.toPlainString() ?: "-").append('|')
            append(pack.terminationRules.noticePeriodDays).append('|')
            append(pack.terminationRules.permittedGrounds.map { it.name }.sorted().joinToString(",")).append('|')
            append(pack.terminationRules.defaultDpdThreshold).append('|')
            pack.disclosures.sortedBy { it.id }.forEach { d ->
                append(d.id).append(':').append(d.templateKey).append(':')
                    .append(d.languages.sorted().joinToString(",")).append(':')
                    .append(d.requiresAcknowledgement).append(':').append(d.stage.name).append(';')
            }
            append('|')
            pack.mandatoryChecks.sortedBy { it.id }.forEach { r ->
                append(r.id).append(':').append(r.attribute.name).append(':').append(r.operator.name).append(':')
                    .append(r.threshold?.toPlainString() ?: "-").append(':')
                    .append(r.values.sorted().joinToString(",")).append(':')
                    .append(r.band ?: "-").append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
