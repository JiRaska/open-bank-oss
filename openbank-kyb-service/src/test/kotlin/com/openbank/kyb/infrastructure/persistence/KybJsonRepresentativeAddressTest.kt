// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence

import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import com.openbank.kyb.infrastructure.persistence.repository.KybJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class KybJsonRepresentativeAddressTest {

    @Test
    fun `an extract stored before representatives had an address still reads back`() {
        // Verbatim shape of a row written by the previous release: no `address` on the representative.
        val stored = """{"scheme":"CZ_ICO","value":"45274649","legalName":"Příklad s.r.o.","legalFormCode":"112",
            "legalFormClass":"LIMITED_COMPANY","status":"ACTIVE","address":null,"incorporatedOn":"2010-01-01",
            "taxId":null,"representatives":[{"fullName":"Jana Nováková","dateOfBirth":"1980-05-05",
            "body":"jednatelé","role":"jednatel","since":"2015-01-01"}],"ruleMode":"SOLE","ruleRequired":1,
            "ruleText":null,"otherIdentifiers":{},"source":"ares","sourceRef":null,"verification":"VERIFIED",
            "fetchedAt":"2026-09-01T10:00:00Z"}"""
        val ex = KybJson.readExtract(stored)
        assertThat(ex.representatives.single().fullName).isEqualTo("Jana Nováková")
        assertThat(ex.representatives.single().address).isNull()
    }

    @Test
    fun `a representative address round-trips`() {
        val address = RegisteredAddress("Hlavní 1", "Praha", "11000", "CZ")
        val ex = RegistryExtract(
            identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
            legalName = "Příklad s.r.o.",
            legalFormCode = "112",
            legalFormClass = LegalFormClass.LIMITED_COMPANY,
            status = EntityStatus.ACTIVE,
            registeredAddress = null,
            incorporatedOn = null,
            taxId = null,
            representatives = listOf(Representative("Jana Nováková", null, "jednatelé", "jednatel", null, address)),
            representationRule = RepresentationRule.SOLE,
            source = "ares",
            sourceRef = null,
            verification = ExtractVerification.VERIFIED,
            fetchedAt = Instant.parse("2026-09-01T10:00:00Z"),
        )
        assertThat(KybJson.readExtract(KybJson.write(ex)).representatives.single().address).isEqualTo(address)
    }
}
