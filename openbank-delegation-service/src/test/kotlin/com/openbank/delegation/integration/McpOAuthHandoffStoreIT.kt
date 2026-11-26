// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.infrastructure.persistence.repository.McpConnectRequestStore
import com.openbank.delegation.infrastructure.persistence.repository.McpOAuthHandoffStore
import com.openbank.delegation.it.PostgresTestResource
import com.openbank.libs.security.McpConnectContext
import com.openbank.libs.security.McpConnectContextV2
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class McpOAuthHandoffStoreIT {
    @Inject
    lateinit var handoffs: McpOAuthHandoffStore

    @Inject
    lateinit var requests: McpConnectRequestStore

    @Test
    fun `concurrent claims register one visible request and allow same owner retry`(): Unit = onEventLoop {
        val binding = binding()
        val token = reference()
        val parties = listOf(claim(), claim())
        assertThat(handoffs.issue(token, binding)).isTrue()
        assertThat(requests.findForParty(parties.first().partyId, binding.requestRef)).isNull()
        val results = coroutineScope {
            val first = async { handoffs.claimAndRegister(token, parties.first()) }
            val second = async { handoffs.claimAndRegister(token, parties.last()) }
            listOf(first.await(), second.await())
        }
        assertThat(results).containsExactlyInAnyOrder(binding.requestRef, null)
        val owner = parties[results.indexOf(binding.requestRef)]
        val other = parties.first { it != owner }
        assertThat(handoffs.claimAndRegister(token, owner)).isEqualTo(binding.requestRef)
        assertThat(handoffs.claimAndRegister(token, other)).isNull()
        assertThat(requests.findForParty(owner.partyId, binding.requestRef)).isNotNull()
        assertThat(requests.findForParty(other.partyId, binding.requestRef)).isNull()
        assertThat(handoffs.claimedParty(token, binding)).isEqualTo(owner.partyId)
        val changedExpiry = binding.copy(expiresAtEpochSecond = binding.expiresAtEpochSecond + 1)
        assertThat(handoffs.claimedParty(token, changedExpiry)).isNull()
    }

    @Test
    fun `registration conflict rolls back claim`(): Unit = onEventLoop {
        val binding = binding()
        val token = reference()
        val owner = claim()
        val other = claim()
        assertThat(handoffs.issue(token, binding)).isTrue()
        assertThat(requests.register(binding.request(owner.partyId))).isTrue()
        assertThat(handoffs.claimAndRegister(token, other)).isNull()
        assertThat(handoffs.claimedParty(token, binding)).isNull()
        assertThat(handoffs.claimAndRegister(token, owner)).isEqualTo(binding.requestRef)
        assertThat(handoffs.claimedParty(token, binding)).isEqualTo(owner.partyId)
    }

    @Test
    fun `exact selected proof is attested and consumed once without changing SCA state`(): Unit = onEventLoop {
        val binding = binding()
        val token = reference()
        val owner = claim()
        val accountId = UUID.randomUUID()
        val capabilities = setOf("ACCOUNT_READ_BALANCES")
        val base = McpConnectContext(
            binding.clientId,
            "client.test",
            binding.resource,
            "openid",
            binding.requestRef,
            binding.expiresAtEpochSecond,
        )
        val accounts = listOf(McpConnectContextV2.AccountScope(accountId.toString(), capabilities))
        val digest = McpConnectContextV2.of(base, owner.partyId.toString(), null, accounts).payloadSha256()
        assertThat(handoffs.issue(token, binding)).isTrue()
        assertThat(handoffs.claimAndRegister(token, owner)).isEqualTo(binding.requestRef)
        assertThat(handoffs.attestSelectedContext(token, binding, digest)).isFalse()
        assertThat(handoffs.markProven(binding.requestRef, owner, digest)).isFalse()
        val selection = listOf(McpConnectRequestStore.AccountSelection(accountId, capabilities))
        assertThat(requests.select(owner.partyId, binding.requestRef, selection)).isTrue()
        assertThat(handoffs.attestSelectedContext(token, binding, reference())).isFalse()
        assertThat(handoffs.attestSelectedContext(token, binding, digest)).isTrue()
        assertThat(handoffs.attestSelectedContext(token, binding, digest)).isTrue()
        assertThat(handoffs.selectedBinding(binding.requestRef, owner)?.contextSha256).isEqualTo(digest)
        assertThat(handoffs.selectedBinding(binding.requestRef, owner.copy(keycloakSubject = "other"))).isNull()
        assertThat(handoffs.markProven(binding.requestRef, owner, reference())).isFalse()
        assertThat(handoffs.consume(token, binding)).isNull()
        assertThat(handoffs.markProven(binding.requestRef, owner, digest)).isTrue()
        assertThat(handoffs.attestSelectedContext(token, binding, digest)).isTrue()
        assertThat(handoffs.consume(token, binding)).isEqualTo(McpOAuthHandoffStore.Receipt(owner, digest))
        assertThat(handoffs.consume(token, binding)).isNull()
        assertThat(requests.findForParty(owner.partyId, binding.requestRef)?.contextSha256).isNull()
    }

    private fun reference() = UUID.randomUUID().toString().replace("-", "").repeat(2)

    private fun claim() = McpOAuthHandoffStore.Claim(UUID.randomUUID(), UUID.randomUUID().toString())

    private fun binding() = McpOAuthHandoffStore.Binding(
        requestRef = reference(),
        issuer = "https://issuer.test/realms/customers",
        clientId = "synthetic-hosted-client",
        resource = "https://mcp.test/customer/mcp",
        redirectUri = "https://client.test/callback",
        sessionRef = reference(),
        kind = McpOAuthHandoffStore.BindingKind.HOSTED,
        parJkt = null,
        scopes = setOf("openid"),
        expiresAtEpochSecond = Instant.now().plusSeconds(120).epochSecond,
    )

    private fun McpOAuthHandoffStore.Binding.request(party: UUID) = McpConnectRequestStore.Request(
        requestRef = requestRef,
        partyId = party,
        clientId = clientId,
        parJkt = parJkt,
        redirectHost = "client.test",
        resource = resource,
        scopes = scopes,
        expiresAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(expiresAtEpochSecond), ZoneOffset.UTC),
    )

    private fun <T> onEventLoop(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { block() }
    }
}
