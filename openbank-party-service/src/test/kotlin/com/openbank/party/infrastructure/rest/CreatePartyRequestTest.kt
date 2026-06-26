// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.party.infrastructure.rest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Contract guard for the CreatePartyRequest ↔ command boundary (the empty-body-400 footgun).
 *
 * `email` is mandatory in openapi.yaml (required: [...email]) and downstream (Party / PartyEntity
 * NOT NULL + unique). The DTO field is nullable only so a request that omits it deserialises and
 * trips our explicit check — rather than Jackson hard-failing on a missing non-null Kotlin field
 * and returning a silent, body-less 400. This test pins that the check fires with a clear message.
 */
class CreatePartyRequestTest {

    private fun req(email: String?) = CreatePartyRequest(
        partyType = "INDIVIDUAL", legalName = "Jan Novak", tradingName = null,
        dateOfBirth = null, nationality = null, taxId = null,
        registrationNumber = null, email = email, phone = null, address = null,
    )

    @Test
    fun `toCommand maps a populated request`() {
        val cmd = req("jan.novak@openbank.test").toCommand("idem-1")
        assertThat(cmd.email).isEqualTo("jan.novak@openbank.test")
        assertThat(cmd.idempotencyKey).isEqualTo("idem-1")
    }

    @Test
    fun `toCommand rejects a missing email with a clear message`() {
        assertThatThrownBy { req(null).toCommand("idem-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("email is required")
    }

    @Test
    fun `toCommand rejects a blank email with a clear message`() {
        assertThatThrownBy { req("   ").toCommand("idem-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("email is required")
    }
}
