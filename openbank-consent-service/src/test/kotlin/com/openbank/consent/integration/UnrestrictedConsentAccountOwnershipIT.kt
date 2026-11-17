// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * An UNRESTRICTED consent (`accountIbans == null`, "the party's own accounts") covers an IBAN only
 * when account-service confirms the consenting party owns it — proven over real HTTP: the validate
 * endpoint, the real rest-client with its named oidc-client `m2m`, and an in-process stand-in for
 * account-service's ownership projection and Keycloak's token endpoint.
 *
 * Each case also asserts what reached the stand-in (the party the consent names, the consent
 * client's bearer), so a validate that never asked — or asked about the wrong party — cannot pass.
 */
@QuarkusTest
@QuarkusTestResource(UnrestrictedConsentAccountOwnershipIT.FakeAccountService::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class UnrestrictedConsentAccountOwnershipIT {

    class FakeAccountService : QuarkusTestResourceLifecycleManager {
        private lateinit var http: HttpServer

        companion object {
            /** Bodies and Authorization headers the stand-in received, in arrival order. */
            val requests = ConcurrentLinkedQueue<String>()
            val authorizations = ConcurrentLinkedQueue<String>()
        }

        override fun start(): Map<String, String> {
            http = HttpServer.create(InetSocketAddress("127.0.0.1", STUB_PORT), 0)
            http.createContext("/token") { ex ->
                val body = """{"access_token":"$CONSENT_TOKEN","token_type":"Bearer","expires_in":300}"""
                    .toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            http.createContext("/api/v1/accounts/ownership-verifications") { ex ->
                val request = ex.requestBody.use { String(it.readAllBytes()) }
                requests.add(request)
                authorizations.add(ex.requestHeaders.getFirst("Authorization").orEmpty())
                if (request.contains(FAILING_IBAN)) {
                    ex.sendResponseHeaders(503, -1)
                    ex.close()
                    return@createContext
                }
                val owned = request.contains(OWNED_IBAN) && request.contains(OWNER_PARTY.toString())
                val body = if (owned) {
                    """{"owned":true,"active":true,"accountId":"11111111-2222-4333-8444-555555555555"}"""
                } else {
                    """{"owned":false,"active":false}"""
                }.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            http.executor = Executors.newSingleThreadExecutor()
            http.start()
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("consent-events-out").toMutableMap()
            props["openbank.outbox.dispatch-enabled"] = "false"
            props["quarkus.rest-client.account-service.url"] = "http://127.0.0.1:$STUB_PORT"
            props["quarkus.oidc-client.m2m.auth-server-url"] = "http://127.0.0.1:$STUB_PORT"
            props["quarkus.oidc-client.m2m.discovery-enabled"] = "false"
            props["quarkus.oidc-client.m2m.token-path"] = "/token"
            props["quarkus.oidc-client.auth-server-url"] = "http://127.0.0.1:$STUB_PORT"
            props["quarkus.oidc-client.discovery-enabled"] = "false"
            props["quarkus.oidc-client.token-path"] = "/token"
            return props
        }

        override fun stop() {
            http.stop(0)
            InMemoryConnector.clear()
        }
    }

    @Inject
    lateinit var dataSource: DataSource

    private val requests get() = FakeAccountService.requests
    private val authorizations get() = FakeAccountService.authorizations

    @BeforeEach
    fun reset() {
        requests.clear()
        authorizations.clear()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an IBAN another party holds is not covered by an unrestricted consent`() {
        val id = activeConsent(accountIbans = null)

        val code = validate(id, FOREIGN_IBAN, expectValid = false)

        assertThat(code).isEqualTo("CONSENT_ACCOUNT_NOT_OWNED")
        assertThat(requests).describedAs("account-service was asked about the consent's own party").singleElement()
            .satisfies({ assertThat(it).contains(FOREIGN_IBAN).contains(OWNER_PARTY.toString()) })
        assertThat(authorizations).containsExactly("Bearer $CONSENT_TOKEN")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an IBAN the consenting party owns is covered by an unrestricted consent`() {
        val id = activeConsent(accountIbans = null)

        validate(id, OWNED_IBAN, expectValid = true)

        assertThat(requests).singleElement().satisfies({ assertThat(it).contains(OWNED_IBAN) })
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an ownership lookup that fails denies rather than passes`() {
        val id = activeConsent(accountIbans = null)

        val code = validate(id, FAILING_IBAN, expectValid = false)

        assertThat(code).isEqualTo("CONSENT_ACCOUNT_UNVERIFIABLE")
        assertThat(requests).describedAs("the failure came from the lookup, not from skipping it").isNotEmpty
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an explicit account list is decided without asking account-service`() {
        val id = activeConsent(accountIbans = listOf(OWNED_IBAN))

        validate(id, OWNED_IBAN, expectValid = true)
        assertThat(validate(id, FOREIGN_IBAN, expectValid = false)).isEqualTo("CONSENT_ACCOUNT_NOT_COVERED")

        assertThat(requests).isEmpty()
    }

    private fun validate(id: UUID, iban: String, expectValid: Boolean): String? {
        val path = Given {
            contentType("application/json")
            body("""{"granteeId":"$GRANTEE","requiredScope":"ACCOUNTS_READ","accountIban":"$iban"}""")
        } When {
            post("/api/v1/consents/$id/validate")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath()
        }
        assertThat(path.getBoolean("valid")).describedAs("valid for %s", iban).isEqualTo(expectValid)
        return path.getString("code")
    }

    /** Created through the real endpoint, then made ACTIVE in the database (SCA is out of scope). */
    private fun activeConsent(accountIbans: List<String>?): UUID {
        val ibans = accountIbans?.joinToString(",", "[", "]") { "\"$it\"" } ?: "null"
        val id = Given {
            contentType("application/json")
            body(
                """
                {"partyId":"$OWNER_PARTY","granteeId":"$GRANTEE","granteeType":"TPP","granteeName":"IT TPP",
                "scopes":["ACCOUNTS_READ"],"accountIbans":$ibans,
                "validTo":"${OffsetDateTime.now().plusDays(30)}","redirectUri":null,"tppTransactionId":null}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        val consentId = UUID.fromString(id)
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement("UPDATE consents SET status = 'ACTIVE' WHERE id = ?")
            ps.setObject(1, consentId)
            assertThat(ps.executeUpdate()).isEqualTo(1)
        }
        return consentId
    }

    companion object {
        // Literals: a QuarkusTestResource loads in another classloader, so nothing randomised here.
        private const val STUB_PORT = 18216
        private const val CONSENT_TOKEN = "consent-m2m-token"
        private const val GRANTEE = "tpp-ownership-it"
        private val OWNER_PARTY: UUID = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")
        private const val OWNED_IBAN = "CZ6508000000192000145399"
        private const val FOREIGN_IBAN = "CZ7108000000192000145399"
        private const val FAILING_IBAN = "CZ5508000000192000145399"
    }
}
