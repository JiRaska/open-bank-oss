// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.security.LocalAesGcmFieldProtector
import com.openbank.mcp.DockerRequirement
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.AuthorizationRequest
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.TokenAuthentication
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.digest
import com.openbank.mcp.infrastructure.oauth.PgHostedOAuthTransactions.Kind
import io.mockk.coEvery
import io.mockk.mockk
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.PoolOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.sql.DriverManager
import java.util.UUID

class PgHostedOAuthTransactionsIT {
    @Test
    fun `callback and authorization code bind and consume atomically in PostgreSQL`() {
        DockerRequirement.require("PgHostedOAuthTransactionsIT")
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).use { postgres ->
            postgres.start()
            applyMigrations(postgres)
            runBlocking {
                val vertx = Vertx.vertx()
                val connect = PgConnectOptions()
                    .setHost(postgres.host)
                    .setPort(postgres.firstMappedPort)
                    .setDatabase(postgres.databaseName)
                    .setUser(postgres.username)
                    .setPassword(postgres.password)
                val pool = PgPool.pool(vertx, connect, PoolOptions())
                try {
                    verifyStore(pool)
                    verifyApprovedCallback(pool)
                    verifyGrantRotation(pool)
                } finally {
                    pool.close().awaitSuspending()
                    vertx.close().awaitSuspending()
                }
            }
        }
    }

    private fun applyMigrations(postgres: PostgreSQLContainer<*>) {
        val migrations = listOf(
            "V3__hosted_oauth_transactions.sql",
            "V4__hosted_bank_grant_custody.sql",
            "V5__hosted_oauth_vendor_grants.sql",
        )
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { jdbc ->
            jdbc.createStatement().use { statement ->
                migrations.forEach { migration ->
                    val resource = checkNotNull(javaClass.getResource("/db/migration/$migration"))
                    statement.execute(resource.readText())
                }
            }
        }
    }

    private suspend fun verifyGrantRotation(pool: PgPool) = kotlinx.coroutines.coroutineScope {
        val vendor = Vendor.CHATGPT
        val connection = UUID.randomUUID()
        var active = true
        var usable = true
        val store = PgHostedVendorGrants(pool)
        val grants = HostedVendorGrants(
            store,
            { incoming -> vendor.takeIf { incoming.clientId == vendor.clientId && incoming.proof == "verified" } },
            { id, current -> active && id == vendor && current == connection },
            { id, current -> active && usable && id == vendor && current == connection },
            setOf(vendor),
        )
        val credentials = HostedVendorAuthentication.Credentials(
            vendor.clientId,
            TokenAuthentication.PRIVATE_KEY_JWT,
            "verified",
        )
        val original = checkNotNull(grants.issue(vendor, connection))
        assertThat(grants.admit(original.accessToken)?.connectionId).isEqualTo(connection)
        assertThat(grants.admit(original.refreshToken)).isNull()
        assertThat(HostedVendorGrants.allowsTool("propose_payment")).isFalse()
        val concurrent = List(4) {
            async { grants.refresh(original.refreshToken, credentials, HostedOAuthAdmission.RESOURCE, null) }
        }.awaitAll().filterNotNull()
        assertThat(concurrent).hasSize(1)
        assertThat(grants.admit(concurrent.single().accessToken)).isNull()
        assertThat(grants.admit(original.accessToken)).isNull()

        val prePrune = checkNotNull(grants.issue(vendor, connection))
        val afterPrune = checkNotNull(
            grants.refresh(prePrune.refreshToken, credentials, HostedOAuthAdmission.RESOURCE, null),
        )
        pool.preparedQuery(
            "UPDATE mcp_hosted_vendor_tokens SET expires_at = now() WHERE token_digest = $1",
        ).execute(Tuple.of(digest(prePrune.refreshToken))).awaitSuspending()
        store.deleteExpiredBatch()
        assertThat(grants.admit(afterPrune.accessToken)?.connectionId).isEqualTo(connection)
        usable = false
        assertThat(grants.refresh(prePrune.refreshToken, credentials, HostedOAuthAdmission.RESOURCE, null)).isNull()
        usable = true
        assertThat(grants.admit(afterPrune.accessToken)).isNull()

        val next = checkNotNull(grants.issue(vendor, connection))
        usable = false
        assertThat(grants.refresh(next.refreshToken, credentials, HostedOAuthAdmission.RESOURCE, null)).isNull()
        usable = true
        active = false
        assertThat(grants.admit(next.accessToken)).isNull()
        assertThat(grants.refresh(next.refreshToken, credentials, HostedOAuthAdmission.RESOURCE, null)).isNull()
        active = true
        grants.revoke(vendor, connection)
        assertThat(grants.admit(next.accessToken)).isNull()
    }

    private suspend fun verifyApprovedCallback(pool: PgPool) {
        val store = PgHostedOAuthTransactions(pool)
        val protector = LocalAesGcmFieldProtector(mapOf(1 to ByteArray(32) { 9 }), ByteArray(32) { 7 })
        val mapper = ObjectMapper()
        val vendorVerifier = "V".repeat(43)
        val vendor = Vendor.CHATGPT
        val expectedAuthorize = URI.create("https://bank.example/authorize?client_id=bank&request_uri=test")
        val par = mockk<HostedBankParClient>()
        var bankState = ""
        var bankChallenge = ""
        coEvery { par.push(vendor, any(), any()) } answers {
            bankState = secondArg()
            bankChallenge = thirdArg()
            expectedAuthorize
        }
        val requestToStart = AuthorizationRequest(
            vendor.clientId,
            vendor.callback,
            "code",
            digest(vendorVerifier),
            "S256",
            "vendor-state",
            HostedOAuthAdmission.OUTER_SCOPE,
            HostedOAuthAdmission.RESOURCE,
        )
        val start = HostedOAuthStart(store, par, protector, mapper, setOf(vendor))
        val pending = checkNotNull(start.authorize(requestToStart, null))
        val browser = pending.browserBinding
        assertThat(pending.bankAuthorize).isEqualTo(expectedAuthorize)
        assertThat(bankState).hasSize(43).isNotEqualTo(browser)
        assertThat(bankChallenge).hasSize(43).isNotEqualTo(digest(vendorVerifier))

        val connection = UUID.randomUUID()
        val complete = HostedOAuthComplete(
            store,
            { vendor, code, verifier ->
                assertThat(vendor).isEqualTo(Vendor.CHATGPT)
                assertThat(code).isEqualTo("approved-bank-code")
                assertThat(digest(verifier)).isEqualTo(bankChallenge)
                HostedBankCodeExchange.VerifiedConnection(connection, vendor)
            },
            { credentials -> Vendor.CHATGPT.takeIf { credentials.proof == "verified-assertion" } },
            { vendor, id ->
                HostedVendorTokens("synthetic-opaque-grant", "synthetic-refresh")
                    .takeIf { vendor == Vendor.CHATGPT && id == connection }
            },
            protector,
            mapper,
            setOf(Vendor.CHATGPT),
        )
        assertThat(complete.callback(bankState, "D".repeat(43), "approved-bank-code")).isNull()
        val redirect = checkNotNull(complete.callback(bankState, browser, "approved-bank-code"))
        assertThat(redirect.query).contains("state=vendor-state")
        verifyRedemption(complete, redirect, vendorVerifier)
        assertThat(complete.callback(bankState, browser, "approved-bank-code")).isNull()
    }

    private suspend fun verifyRedemption(complete: HostedOAuthComplete, redirect: URI, vendorVerifier: String) {
        val code = redirect.query.substringAfter("code=").substringBefore("&")
        val credentials = HostedVendorAuthentication.Credentials(
            Vendor.CHATGPT.clientId,
            TokenAuthentication.PRIVATE_KEY_JWT,
            "verified-assertion",
        )
        val request = HostedOAuthComplete.Redemption(
            code,
            vendorVerifier,
            Vendor.CHATGPT.callback,
            HostedOAuthAdmission.RESOURCE,
        )
        val wrongProof = HostedVendorAuthentication.Credentials(
            Vendor.CHATGPT.clientId,
            TokenAuthentication.PRIVATE_KEY_JWT,
            "wrong",
        )
        assertThat(complete.redeem(request, wrongProof)).isNull()
        assertThat(complete.redeem(request, credentials)?.accessToken).isEqualTo("synthetic-opaque-grant")
        assertThat(complete.redeem(request, credentials)).isNull()
    }

    private suspend fun verifyStore(pool: PgPool) = kotlinx.coroutines.coroutineScope {
        val store = PgHostedOAuthTransactions(pool)
        val handle = "A".repeat(43)
        val binding = "B".repeat(43)
        val badBinding = "C".repeat(43)
        val payload = byteArrayOf(1, 2, 3)

        assertThat(store.put(Kind.BANK_CALLBACK, handle, Vendor.CHATGPT, binding, payload)).isTrue()
        assertThat(store.put(Kind.BANK_CALLBACK, handle, Vendor.CLAUDE, binding, payload)).isFalse()
        assertThat(store.consumeBankCallback(handle, badBinding)).isNull()
        assertThat(store.consumeBankCallback(handle, binding)?.sealedPayload).isEqualTo(payload)
        assertThat(store.consumeBankCallback(handle, binding)).isNull()

        assertThat(store.put(Kind.VENDOR_CODE, handle, Vendor.CHATGPT, binding, payload)).isTrue()
        assertThat(store.consumeBankCallback(handle, binding)).isNull()
        assertThat(store.consumeVendorCode(handle, binding, Vendor.CLAUDE)).isNull()
        assertThat(store.consumeVendorCode(handle, badBinding, Vendor.CHATGPT)).isNull()
        val winners = List(8) { async { store.consumeVendorCode(handle, binding, Vendor.CHATGPT) } }.awaitAll()
            .filterNotNull()
        assertThat(winners).hasSize(1)
        assertThat(winners.single().sealedPayload).isEqualTo(payload)

        assertThat(store.put(Kind.VENDOR_CODE, handle, Vendor.CHATGPT, binding, payload)).isTrue()
        pool.query("UPDATE mcp_hosted_oauth_transactions SET expires_at = now()").execute().awaitSuspending()
        assertThat(store.consumeVendorCode(handle, binding, Vendor.CHATGPT)).isNull()
        assertThat(store.deleteExpiredBatch()).isEqualTo(1)
    }
}
