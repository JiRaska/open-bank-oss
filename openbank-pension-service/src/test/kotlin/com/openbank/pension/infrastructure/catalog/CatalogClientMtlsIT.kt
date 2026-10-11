// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.catalog

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.openbank.pension.infrastructure.identity.AccountRestClient
import com.openbank.pension.infrastructure.identity.OwnershipVerificationRequestDto
import com.openbank.pension.infrastructure.identity.PartyRestClient
import com.openbank.pension.infrastructure.identity.ScaConsumeRequestDto
import com.openbank.pension.infrastructure.identity.ScaConsumeRestClient
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Enables the same OIDC client filter used by the production catalog REST client. */
class CatalogClientOidcEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("quarkus.oidc-client.enabled" to "true")
}

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(CatalogClientMtlsTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogClientOidcEnabledProfile::class)
class CatalogClientMtlsIT {
    @RestClient
    @Inject
    lateinit var client: StrategyCatalogRestClient

    @RestClient
    @Inject
    lateinit var accountClient: AccountRestClient

    @RestClient
    @Inject
    lateinit var partyClient: PartyRestClient

    @RestClient
    @Inject
    lateinit var scaClient: ScaConsumeRestClient

    @Test
    fun `real clients mint OIDC token and succeed with trusted mTLS peer`(): Unit = runBlocking {
        assertThat(client.offerings()).isEmpty()

        val account = accountClient.verifyOwnership(
            OwnershipVerificationRequestDto(IBAN, UUID.fromString(OWNER_PARTY_ID)),
        )
        assertThat(account.owned).isTrue()
        assertThat(account.active).isTrue()
        assertThat(account.accountId).isEqualTo(UUID.fromString(ACCOUNT_ID))

        val party = partyClient.party(UUID.fromString(PARTY_ID))
        assertThat(party.id).isEqualTo(UUID.fromString(PARTY_ID))
        assertThat(party.partyType).isEqualTo("INDIVIDUAL")
        assertThat(party.status).isEqualTo("ACTIVE")
        assertThat(party.kycStatus).isEqualTo("APPROVED")

        val challenge = scaClient.consume(
            UUID.fromString(CHALLENGE_ID),
            ScaConsumeRequestDto(
                UUID.fromString(SCA_PARTY_ID),
                "pension-exit:$SIGNED_HASH",
                SIGNED_HASH,
            ),
        )
        assertThat(challenge.id).isEqualTo(UUID.fromString(CHALLENGE_ID))
        assertThat(challenge.partyId).isEqualTo(UUID.fromString(SCA_PARTY_ID))
        assertThat(challenge.purpose).isEqualTo("APPROVAL")
        assertThat(challenge.status).isEqualTo("COMPLETED")

        val servers = checkNotNull(CatalogTlsTestState.servers)
        // Reactive and annotation-based filters have separate token caches; cache count is not the transport contract.
        servers.token.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/token")))
        servers.catalog.verify(
            1,
            getRequestedFor(urlEqualTo("/api/v2/offerings"))
                .withHeader("Authorization", equalTo("Bearer test-token")),
        )
        servers.catalog.verify(
            1,
            postRequestedFor(urlEqualTo(ACCOUNT_PATH))
                .withHeader("Authorization", equalTo("Bearer test-token")),
        )
        servers.catalog.verify(
            1,
            getRequestedFor(urlEqualTo("/api/v1/parties/$PARTY_ID"))
                .withHeader("Authorization", equalTo("Bearer test-token")),
        )
        servers.catalog.verify(
            1,
            postRequestedFor(urlEqualTo("/api/v1/sca/challenges/$CHALLENGE_ID/consume"))
                .withHeader("Authorization", equalTo("Bearer test-token")),
        )
    }

    private companion object {
        const val IBAN = "CZ0000000000000000000000"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val PARTY_ID = "d1d1d1d1-e2e2-4f4f-8a8a-b1b1b1b1b1b1"
        const val SCA_PARTY_ID = "7e5a0002-0000-4000-8000-000000000335"
        const val CHALLENGE_ID = "7e5a0001-0000-4000-8000-000000000335"
        const val SIGNED_HASH = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val ACCOUNT_PATH = "/api/v1/accounts/ownership-verifications"
    }
}

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(CatalogClientWrongCaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogClientOidcEnabledProfile::class)
class CatalogClientWrongCaIT {
    @RestClient
    @Inject
    lateinit var client: StrategyCatalogRestClient

    @Test
    fun `real catalog client rejects a server outside the configured trust store`() {
        assertCatalogHandshakeRejected { runBlocking { client.offerings() } }

        val servers = checkNotNull(CatalogTlsTestState.servers)
        servers.token.verify(1, postRequestedFor(urlEqualTo("/token")))
        servers.catalog.verify(0, getRequestedFor(urlEqualTo("/api/v2/offerings")))
    }
}

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(CatalogClientWrongHostnameTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogClientOidcEnabledProfile::class)
class CatalogClientWrongHostnameIT {
    @RestClient
    @Inject
    lateinit var client: StrategyCatalogRestClient

    @Test
    fun `real catalog client rejects a trusted certificate for the wrong hostname`() {
        assertCatalogHandshakeRejected { runBlocking { client.offerings() } }

        val servers = checkNotNull(CatalogTlsTestState.servers)
        servers.token.verify(1, postRequestedFor(urlEqualTo("/token")))
        servers.catalog.verify(0, getRequestedFor(urlEqualTo("/api/v2/offerings")))
    }
}

private fun assertCatalogHandshakeRejected(call: () -> Unit) {
    val failure = catchThrowable { call() }
    assertThat(failure).isNotNull()
    val causes = generateSequence(checkNotNull(failure)) { it.cause }.toList()
    assertThat(causes).anyMatch { it is SSLHandshakeException || it is SSLPeerUnverifiedException }
}
