// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.openbank.kyb.application.port.out.UboAdapter
import com.openbank.kyb.domain.model.CountryPack
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant

/**
 * The fallback for every jurisdiction whose beneficial-ownership register this platform cannot
 * query — which today is all of them except the UK. The Czech `Evidence skutečných majitelů` is
 * the worked example: it exists, it is authoritative, and it publishes no usable public API, so the
 * only lawful path is to ask the customer and record what they said (`cz-v1.json` says exactly
 * that: `publicApi: false`, `fallback: SELF_DECLARATION`).
 *
 * It returns a finding rather than null on purpose. A null would make "this jurisdiction has no
 * queryable register" indistinguishable from "nobody looked", and the two need different work: one
 * is a declaration to collect from the customer, the other is a bug. The finding carries no owners
 * and `requiresDeclaration = true`, so the case cannot silently proceed as though ownership were
 * established.
 *
 * This adapter deliberately does NOT invent an owner from the representatives. A director is not a
 * beneficial owner, the two coincide often enough for the mistake to look right in testing, and a
 * fabricated UBO is worse than a missing one — it satisfies the evidence requirement while being
 * unevidenced.
 */
@ApplicationScoped
class SelfDeclarationUboAdapter : UboAdapter {

    @Inject lateinit var clock: Clock

    override val source: String = SOURCE

    /** Answers for everything: [UboRouter] consults it only after every register-backed adapter declined. */
    override fun supports(scheme: IdentifierScheme): Boolean = true

    override suspend fun lookup(identifier: LegalEntityIdentifier, pack: CountryPack): UboFinding = UboFinding(
        identifier = identifier,
        source = UboSource.SELF_DECLARATION,
        owners = emptyList(),
        registerStatements = emptyList(),
        threshold = pack.uboRegister.threshold,
        registerName = pack.uboRegister.name,
        sourceRef = null,
        fetchedAt = Instant.now(clock),
    )

    companion object {
        const val SOURCE = "self-declaration"
    }
}
