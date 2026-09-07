// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.application.port.out.RegistryAdapter
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationRule
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant

/**
 * The fallback for a scheme with no free machine-readable register (ADR-0284 D1): the applicant's
 * own declaration becomes an UNVERIFIED extract with no representatives and an UNKNOWN
 * representation rule, so the case lands in MANUAL_REVIEW and an operator attests it against the
 * uploaded extract. Nothing automatic is derived from a self-declaration.
 */
@ApplicationScoped
class ManualAttestationRegistryAdapter : RegistryAdapter {

    @Inject lateinit var clock: Clock

    override val source: String = "manual-attestation"

    override fun supports(scheme: IdentifierScheme): Boolean = true

    override suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract? {
        val d = declared ?: return null
        return RegistryExtract(
            identifier = identifier,
            legalName = d.legalName.trim(),
            legalFormCode = null,
            legalFormClass =
            d.legalFormClass?.let {
                runCatching { LegalFormClass.valueOf(it) }.getOrNull()
            } ?: LegalFormClass.OTHER,
            status = EntityStatus.UNKNOWN,
            registeredAddress = RegisteredAddress(d.addressLine1, d.city, d.postalCode, d.countryCode.uppercase()),
            incorporatedOn = null,
            taxId = null,
            representatives = emptyList(),
            representationRule = RepresentationRule.UNKNOWN,
            source = source,
            sourceRef = null,
            verification = ExtractVerification.UNVERIFIED,
            fetchedAt = Instant.now(clock),
        )
    }
}
