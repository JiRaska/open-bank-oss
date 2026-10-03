// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.LendingGraphProofUnavailable
import io.mockk.every
import io.mockk.mockk
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class LendingGraphProofClientsTest {
    private val party = mockk<LendingGraphPartyProofClient>()
    private val document = mockk<LendingGraphDocumentProofClient>()
    private val adapter = RestLendingGraphProofAdapter(party, document)

    @Test
    fun `both source clients select the narrow graph identity`() {
        assertThat(LendingGraphPartyProofClient::class.java.getAnnotation(OidcClientFilter::class.java).value)
            .isEqualTo("lending-graph")
        assertThat(LendingGraphDocumentProofClient::class.java.getAnnotation(OidcClientFilter::class.java).value)
            .isEqualTo("lending-graph")
    }

    @Test
    fun `source decisions are boolean and source outage is not a false match`() {
        val partyId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val loanId = UUID.randomUUID()
        val hash = "a".repeat(64)
        every { party.verify(GuarantorIdentityProofRequest(partyId)) } returns
            Uni.createFrom().item(GuarantorIdentityProofResponse(true))
        every {
            document.verify(SignedGuaranteeProofRequest(documentId, loanId, partyId, "openbank-cz", hash))
        } returns Uni.createFrom().item(SignedGuaranteeProofResponse(false))

        assertThat(runBlocking { adapter.hasVerifiedGuarantorIdentity(partyId) }).isTrue()
        val matches = runBlocking {
            adapter.matchesSignedGuarantee(documentId, loanId, partyId, "openbank-cz", hash)
        }
        assertThat(matches).isFalse()

        every { party.verify(GuarantorIdentityProofRequest(partyId)) } returns
            Uni.createFrom().failure(IllegalStateException("source unavailable"))
        assertThatThrownBy { runBlocking { adapter.hasVerifiedGuarantorIdentity(partyId) } }
            .isInstanceOf(LendingGraphProofUnavailable::class.java)
            .hasMessageContaining("source proof unavailable")
            .hasCauseInstanceOf(IllegalStateException::class.java)
    }
}
