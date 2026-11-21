// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.read

import com.openbank.mcp.application.port.out.ConsentContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** Pins the account-scope semantics of the consent-service /validate response. */
class ConsentGateTest {

    private val ctx = ConsentContext(
        agentId = "agent:mcp-tpp-42",
        consentId = UUID.randomUUID().toString(),
        grantedAccounts = emptyList(),
    )

    // consent-service defines a null accountIbans as "covers all of the party's accounts" and has
    // already checked the named account before answering valid, so null is a vouching, not a gap.
    @Test
    fun `a valid consent with null grantedAccounts allows the named account`() {
        val gate = ConsentGate(FakeConsentValidateClient(valid = true, grantedAccounts = null))

        assertThat(gate.validate(ctx, ConsentScopes.ACCOUNTS_READ, "CZ01").valid).isTrue()
    }

    @Test
    fun `a requested account outside the granted list is refused`() {
        val gate = ConsentGate(FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ02")))

        assertThatThrownBy { gate.validate(ctx, ConsentScopes.ACCOUNTS_READ, "CZ01") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a requested account inside the granted list is allowed`() {
        val gate = ConsentGate(FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01")))

        assertThat(gate.validate(ctx, ConsentScopes.ACCOUNTS_READ, "CZ01").valid).isTrue()
    }

    @Test
    fun `no account requested needs no grantedAccounts`() {
        val gate = ConsentGate(FakeConsentValidateClient(valid = true, grantedAccounts = null))

        assertThat(gate.validate(ctx, ConsentScopes.ACCOUNTS_READ, null).valid).isTrue()
    }
}
