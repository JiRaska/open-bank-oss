// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.domain.model.BeneficialOwner
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.OwnershipBand
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class KybUboJsonTest {
    @Test
    fun `stored observation preserves source identity and ownership band`() {
        val finding = UboFinding(
            identifier = LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"),
            source = UboSource.REGISTER,
            owners = listOf(
                BeneficialOwner(
                    fullName = "Synthetic Owner",
                    dateOfBirth = null,
                    nationality = null,
                    countryOfResidence = null,
                    band = OwnershipBand.PCT_25_TO_50,
                    natureOfControl = listOf("ownership-of-shares-25-to-50-percent"),
                    notifiedOn = null,
                    corporate = false,
                    sourceRecordRef = "/company/OC123456/persons-with-significant-control/individual/1",
                ),
            ),
            registerStatements = emptyList(),
            threshold = 0.25,
            registerName = "Companies House",
            sourceRef = "OC123456",
            fetchedAt = Instant.parse("2026-09-17T10:00:00Z"),
        )

        val stored = KybUboJson.write(finding)

        assertThat(stored).contains("\"schemaVersion\":1")
        assertThat(KybUboJson.read(stored)).isEqualTo(finding)
    }
}
