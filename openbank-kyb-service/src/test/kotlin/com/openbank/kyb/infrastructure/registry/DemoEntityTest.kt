// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.openbank.kyb.domain.model.IdentifierChecksums
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RegistrySearchQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DemoEntityTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC)
    private val demoIco = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, DemoEntity.ICO)

    @Test
    fun `switched off, it answers for nothing — the default must be the safe state`() {
        val off = DemoEntity(false, "Oldřich Vaněk", "Ukázková 1", "Praha", "11000", "CZ", clock)
        assertThat(off.isDemo(demoIco)).isFalse()
        assertThat(off.answersSearch(IdentifierScheme.CZ_ICO, RegistrySearchQuery("OpenBank Demo"))).isFalse()
    }

    @Test
    fun `application yaml ships it OFF, so only an explicit deployment env can turn it on`() {
        val yaml = File("src/main/resources/application.yaml").readText()
        assertThat(yaml).contains("enabled: \${OPENBANK_KYB_DEMO_ENTITY_ENABLED:false}")
    }

    @Test
    fun `switched on, it answers only for its own IČO and only for a search that means it`() {
        val on = DemoEntity(true, "Oldřich Vaněk", "Ukázková 1", "Praha", "11000", "CZ", clock)
        assertThat(on.isDemo(demoIco)).isTrue()
        assertThat(on.isDemo(LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"))).isFalse()
        assertThat(on.answersSearch(IdentifierScheme.CZ_ICO, RegistrySearchQuery("openbank demo"))).isTrue()
        assertThat(on.answersSearch(IdentifierScheme.CZ_ICO, RegistrySearchQuery("Škoda Auto"))).isFalse()
        assertThat(on.answersSearch(IdentifierScheme.SK_ICO, RegistrySearchQuery("OpenBank Demo"))).isFalse()
    }

    @Test
    fun `its IČO is the lowest that passes the checksum, and all-zeros would not`() {
        assertThat(IdentifierChecksums.ico(DemoEntity.ICO)).isTrue()
        assertThat(IdentifierChecksums.ico("00000000")).isFalse()
    }

    @Test
    fun `the extract is unmistakably fictitious and names the configured person as sole representative`() {
        val ex = DemoEntity(true, "Oldřich Vaněk", "Ukázková 1", "Praha", "11000", "CZ", clock).extract()
        assertThat(ex.source).isEqualTo(RegistryExtract.SANDBOX_DEMO_SOURCE)
        assertThat(ex.sourceRef).contains("SANDBOX DEMO")
        assertThat(ex.registeredAddress?.postalCode).isEqualTo("00000")
        assertThat(ex.representatives.map { it.fullName }).containsExactly("Oldřich Vaněk")
        assertThat(ex.representationRule.requiredSigners).isEqualTo(1)
        // A rule text is required so the human attestation step applies to the demo too.
        assertThat(ex.representationRule.sourceText).isNotBlank()
    }

    @Test
    fun `the representative carries a fictitious address the sandbox demo user also carries`() {
        val rep = DemoEntity(true, "Oldřich Vaněk", "Ukázková 1", "Praha", "11000", "CZ", clock)
            .extract().representatives.single()
        assertThat(rep.address).isEqualTo(RegisteredAddress("Ukázková 1", "Praha", "11000", "CZ"))
        val yaml = File("src/main/resources/application.yaml").readText()
        assertThat(yaml).contains(
            "street: \${OPENBANK_KYB_DEMO_ENTITY_REPRESENTATIVE_STREET:Ukázková 1}",
            "city: \${OPENBANK_KYB_DEMO_ENTITY_REPRESENTATIVE_CITY:Praha}",
            "postal-code: \${OPENBANK_KYB_DEMO_ENTITY_REPRESENTATIVE_POSTAL_CODE:11000}",
            "country: \${OPENBANK_KYB_DEMO_ENTITY_REPRESENTATIVE_COUNTRY:CZ}",
        )
    }
}
