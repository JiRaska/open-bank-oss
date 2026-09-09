// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.domain.model.DelegationResourceType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RestResourceOwnershipClientTest {
    private val accountClient = mockk<AccountServiceRestClient>()
    private val cardClient = mockk<CardIssuanceRestClient>()
    private val documentClient = mockk<DocumentServiceRestClient>()
    private val client = RestResourceOwnershipClient(accountClient, cardClient, documentClient)

    @Test
    fun `document partyRef proves ownership`(): Unit = runBlocking {
        val owner = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        coEvery { documentClient.getDocument(documentId) } returns
            DocumentOwnerResponse(documentId, owner.toString())

        assertThat(client.verifyOwnership(owner, DelegationResourceType.DOCUMENT, documentId))
            .isEqualTo(OwnershipVerdict.OWNED)
    }

    @Test
    fun `another document owner is a definitive refusal`(): Unit = runBlocking {
        val owner = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        coEvery { documentClient.getDocument(documentId) } returns
            DocumentOwnerResponse(documentId, UUID.randomUUID().toString())

        assertThat(client.verifyOwnership(owner, DelegationResourceType.DOCUMENT, documentId))
            .isEqualTo(OwnershipVerdict.NOT_OWNED)
    }

    @Test
    fun `malformed document partyRef fails closed as unverifiable`(): Unit = runBlocking {
        val documentId = UUID.randomUUID()
        coEvery { documentClient.getDocument(documentId) } returns
            DocumentOwnerResponse(documentId, "not-a-party-id")

        assertThat(client.verifyOwnership(UUID.randomUUID(), DelegationResourceType.DOCUMENT, documentId))
            .isEqualTo(OwnershipVerdict.UNVERIFIABLE)
    }

    @Test
    fun `payment and statement stay unofferable without an ownership source`(): Unit = runBlocking {
        val owner = UUID.randomUUID()
        val objectId = UUID.randomUUID()

        assertThat(client.verifyOwnership(owner, DelegationResourceType.PAYMENT, objectId))
            .isEqualTo(OwnershipVerdict.UNVERIFIABLE)
        assertThat(client.verifyOwnership(owner, DelegationResourceType.STATEMENT, objectId))
            .isEqualTo(OwnershipVerdict.UNVERIFIABLE)
    }
}
