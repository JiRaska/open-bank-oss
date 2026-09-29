// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import com.openbank.party.infrastructure.rest.toResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The party GET must tell "not a PEP" from "never asked". The column defaults to false, so a raw
 * boolean would present every undeclared person as a known non-PEP — and kyb would then skip their
 * PEP declaration. Asserted on the RESPONSE map, which is what readers see.
 */
class PartyKnownAmlFactsTest {

    private val at = Instant.parse("2026-09-17T10:00:00Z")
    private val undeclared = Party(
        id = UUID.randomUUID(), partyType = PartyType.INDIVIDUAL, status = PartyStatus.ACTIVE,
        legalName = "Jana Nováková", tradingName = null, dateOfBirth = null, nationality = "CZ", taxId = null,
        registrationNumber = null, email = "jana@example.test", phone = null, address = null,
        kycStatus = KycStatus.APPROVED, createdAt = at, updatedAt = at,
    )

    @Test
    fun `a person who never declared a profile has an unknown PEP flag and no derived facts`() {
        val response = undeclared.toResponse()
        assertThat(response).containsEntry("pepFlag", null)
        assertThat(response).containsEntry("pepCategory", null)
        assertThat(response).containsEntry("fatcaStatus", null)
        assertThat(response).containsEntry("crsStatus", null)
    }

    @Test
    fun `a declared non-PEP is a known false`() {
        val declared = undeclared.copy(pepFlag = false, fatcaStatus = "NON_US", crsStatus = "NON_REPORTABLE")
        val response = declared.toResponse()
        assertThat(response).containsEntry("pepFlag", false)
        assertThat(response).containsEntry("fatcaStatus", "NON_US")
        assertThat(response).containsEntry("crsStatus", "NON_REPORTABLE")
    }

    @Test
    fun `a screening-set PEP flag reads true even before any declaration`() {
        val screened = undeclared.copy(pepFlag = true)
        val response = screened.toResponse()
        assertThat(response).containsEntry("pepFlag", true)
        assertThat(response).containsEntry("fatcaStatus", null)
    }

    @Test
    fun `a declared PEP carries its category`() {
        val response = undeclared.copy(pepFlag = true, pepCategory = "MP", fatcaStatus = "NON_US").toResponse()
        assertThat(response).containsEntry("pepFlag", true)
        assertThat(response).containsEntry("pepCategory", "MP")
    }
}
