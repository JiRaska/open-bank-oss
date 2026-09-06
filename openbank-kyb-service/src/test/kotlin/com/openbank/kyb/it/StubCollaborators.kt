// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.it

import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.application.port.out.EntityPartyRequest
import com.openbank.kyb.application.port.out.MandateRequest
import com.openbank.kyb.application.port.out.PartyGateway
import com.openbank.kyb.application.port.out.RegistryAdapter
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test doubles for the two out-of-process collaborators. `@Alternative @Priority` beans in the
 * test tree replace the production adapters for every @QuarkusTest in this module — the ITs are
 * about the REST surface, the persistence and the outbox, not about ARES or party-service.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class StubAresAdapter : RegistryAdapter {
    override val source: String = "ares"
    override fun supports(scheme: IdentifierScheme): Boolean = scheme == IdentifierScheme.CZ_ICO
    override suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract? =
        when (identifier.value) {
            // 45274649 — an s.r.o. with two jednatelé acting jointly.
            "45274649" -> extract(
                identifier,
                LegalFormClass.LIMITED_COMPANY,
                RepresentationRule(RepresentationMode.JOINT_N, 2, "dva jednatelé společně"),
                listOf("Jana Nováková", "Eva Dvořáková"),
            )
            // 26185610 — a sole trader.
            "26185610" -> extract(identifier, LegalFormClass.SOLE_TRADER, RepresentationRule.SOLE, listOf("Jan Novák"))
            else -> null
        }

    private fun extract(id: LegalEntityIdentifier, form: LegalFormClass, rule: RepresentationRule, reps: List<String>) =
        RegistryExtract(
            identifier = id,
            legalName = if (form == LegalFormClass.SOLE_TRADER) reps.first() else "Příklad s.r.o.",
            legalFormCode = if (form == LegalFormClass.SOLE_TRADER) "101" else "112",
            legalFormClass = form,
            status = EntityStatus.ACTIVE,
            registeredAddress = RegisteredAddress("Hlavní 1", "Praha", "11000", "CZ"),
            incorporatedOn = LocalDate.of(2010, 1, 1),
            taxId = "CZ${id.value}",
            representatives = reps.map { Representative(it, LocalDate.of(1980, 1, 1), "jednatelé", "jednatel", null) },
            representationRule = rule,
            source = source,
            sourceRef = "C 12345",
            verification = ExtractVerification.VERIFIED,
            fetchedAt = Instant.now(),
        )
}

@Alternative
@Priority(1)
@ApplicationScoped
class StubPartyGateway : PartyGateway {
    val created = CopyOnWriteArrayList<EntityPartyRequest>()
    val mandates = CopyOnWriteArrayList<MandateRequest>()

    override suspend fun createEntityParty(request: EntityPartyRequest): UUID {
        created += request
        return UUID.nameUUIDFromBytes(request.idempotencyKey.toByteArray())
    }

    override suspend fun grantMandate(request: MandateRequest) {
        mandates += request
    }
}
