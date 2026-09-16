// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.openbank.kyb.domain.czech.CzechRepresentationRuleParser
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RegistrySearchHit
import com.openbank.kyb.domain.model.RegistrySearchQuery
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * A SANDBOX-ONLY fictitious company, so business onboarding can be demonstrated end to end without
 * borrowing a real one: search finds it, the case opens on it, and once the representation rule is
 * attested (the human step is kept, not skipped) the configured person can sign and act for it.
 *
 * Every datum is deliberately impossible, so nobody mistakes it for a register record:
 *  - IČO `00000001` — the lowest IČO that passes the mod-11 checksum (`00000000` does not), and one
 *    ARES answers 404 for;
 *  - postal code `000 00`, which no Czech address has;
 *  - `source` = [RegistryExtract.SANDBOX_DEMO_SOURCE], which the lookup cache refuses to store.
 *
 * Off unless `openbank.kyb.demo-entity.enabled`, and only the sandbox deployment sets it: a register
 * that answers for a company that does not exist is an onboarding bypass anywhere else. The name
 * check is NOT relaxed for it — the initiator still has to be, verifiably, [representativeName].
 */
@ApplicationScoped
class DemoEntity(
    @ConfigProperty(name = "openbank.kyb.demo-entity.enabled", defaultValue = "false")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.kyb.demo-entity.representative-name", defaultValue = "Oldřich Vaněk")
    private val representativeName: String,
    private val clock: Clock,
) {

    fun isDemo(identifier: LegalEntityIdentifier): Boolean = enabled && identifier == IDENTIFIER

    /** A name search that plainly means the demo: it contains "openbank" or "demo". */
    fun answersSearch(scheme: IdentifierScheme, query: RegistrySearchQuery): Boolean {
        if (!enabled || scheme != IdentifierScheme.CZ_ICO) return false
        val folded = CzechRepresentationRuleParser.fold(query.name)
        return SEARCH_KEYS.any { folded.contains(it) }
    }

    fun extract(): RegistryExtract = RegistryExtract(
        identifier = IDENTIFIER,
        legalName = LEGAL_NAME,
        legalFormCode = LIMITED_COMPANY_CODE,
        legalFormClass = LegalFormClass.LIMITED_COMPANY,
        status = EntityStatus.ACTIVE,
        registeredAddress = ADDRESS,
        incorporatedOn = FOUNDED,
        taxId = null,
        representatives = listOf(Representative(representativeName, null, "jednatelé", "jednatel", FOUNDED)),
        representationRule = RepresentationRule(RepresentationMode.SOLE, 1, RULE_TEXT),
        source = RegistryExtract.SANDBOX_DEMO_SOURCE,
        sourceRef = "SANDBOX DEMO — fictitious entity, not in any register",
        verification = ExtractVerification.VERIFIED,
        fetchedAt = Instant.now(clock),
    )

    fun hit(): RegistrySearchHit = RegistrySearchHit(
        identifier = IDENTIFIER,
        name = LEGAL_NAME,
        legalFormCode = LIMITED_COMPANY_CODE,
        registeredAddress = "${ADDRESS.line1}, 000 00 ${ADDRESS.city}",
    )

    companion object {
        const val ICO = "00000001"
        const val LEGAL_NAME = "OpenBank Demo s.r.o."
        private const val LIMITED_COMPANY_CODE = "112"
        private const val RULE_TEXT = "Za společnost jedná jednatel samostatně."
        private val IDENTIFIER = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, ICO)
        private val ADDRESS = RegisteredAddress("Ukázková 0", "Demo", "00000", "CZ")
        private val FOUNDED: LocalDate = LocalDate.of(2026, 1, 1)
        private val SEARCH_KEYS = listOf("openbank", "demo")
    }
}
