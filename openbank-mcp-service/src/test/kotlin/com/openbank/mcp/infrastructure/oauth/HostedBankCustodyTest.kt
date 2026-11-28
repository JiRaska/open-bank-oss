// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.FieldProtector
import com.openbank.mcp.infrastructure.customer.CustomerGrant
import com.openbank.mcp.infrastructure.customer.CustomerGrantSource
import com.openbank.mcp.infrastructure.customer.GrantKey
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import kotlinx.coroutines.runBlocking
import org.jose4j.jws.JsonWebSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class HostedBankCustodyTest {
    @Test
    fun `custody requires a signed exact grant and refuses release after revocation`(): Unit = runBlocking {
        val fixture = Fixture()
        val tokens = fixture.tokens()
        assertNull(fixture.custody.storeApproved(Vendor.CLAUDE, KEY, tokens))
        assertNull(fixture.custody.storeApproved(Vendor.CHATGPT, "B".repeat(43), tokens))
        assertNull(fixture.custody.storeApproved(Vendor.CHATGPT, KEY, fixture.tokens(audience = "unrelated")))
        assertNull(fixture.custody.storeApproved(Vendor.CHATGPT, KEY, fixture.tokens(refreshSid = "other")))
        assertNull(fixture.custody.storeApproved(Vendor.CHATGPT, KEY, tokens))
        fixture.authorized = true
        val result = fixture.custody.storeApproved(Vendor.CHATGPT, KEY, tokens)
        assertEquals(CONNECTION, result?.connectionId)
        assertEquals(fixture.exactKey, fixture.lastLookup)
        assertNull(fixture.custody.storeApproved(Vendor.CHATGPT, KEY, tokens))
        assertNotNull(fixture.custody.loadActive(Vendor.CHATGPT, CONNECTION, KEY))
        assertNull(fixture.custody.loadActive(Vendor.CLAUDE, CONNECTION, KEY))
        fixture.authorized = false
        assertNull(fixture.custody.loadActive(Vendor.CHATGPT, CONNECTION, KEY))
        fixture.custody.eraseRevoked(Vendor.CHATGPT, CONNECTION)
        assertNull(fixture.store.get(Vendor.CHATGPT, CONNECTION))
    }

    @Test
    fun `payment-only consent does not enable a hosted balance connection`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            authorized = true
            capability = "ACCOUNT_PROPOSE_PAYMENT"
        }
        assertNull(fixture.custody.storeApproved(Vendor.CHATGPT, KEY, fixture.tokens()))
    }

    @Test
    fun `rotation fences the prior token and revocation cannot be undone`(): Unit = runBlocking {
        val fixture = Fixture().apply { authorized = true }
        assertNotNull(fixture.custody.storeApproved(Vendor.CHATGPT, KEY, fixture.tokens()))
        fixture.now = fixture.now.plusSeconds(90)
        assertNull(fixture.custody.loadActive(Vendor.CHATGPT, CONNECTION, KEY))
        var calls = 0
        val exchange = HostedBankRefreshExchange { vendor, key, refreshToken ->
            calls += 1
            assertEquals(Vendor.CHATGPT, vendor)
            assertEquals(KEY, key)
            assertNotNull(refreshToken)
            assertFalse(
                fixture.custody.refreshActive(vendor, CONNECTION, key) { _, _, _ ->
                    error("duplicate refresh")
                },
            )
            assertNull(fixture.custody.loadActive(vendor, CONNECTION, key))
            fixture.tokens(revision = 2)
        }
        assertTrue(fixture.custody.refreshActive(Vendor.CHATGPT, CONNECTION, KEY, exchange))
        assertEquals(1, calls)
        assertNotNull(fixture.custody.loadActive(Vendor.CHATGPT, CONNECTION, KEY))
        assertFalse(
            fixture.custody.refreshActive(Vendor.CHATGPT, CONNECTION, KEY) { vendor, _, _ ->
                fixture.custody.eraseRevoked(vendor, CONNECTION)
                fixture.tokens(revision = 3)
            },
        )
        assertNull(fixture.custody.loadActive(Vendor.CHATGPT, CONNECTION, KEY))
    }

    @Test
    fun `refusal before outbound exchange leaves custody available for a later authorized retry`(): Unit = runBlocking {
        val fixture = Fixture().apply { authorized = true }
        assertNotNull(fixture.custody.storeApproved(Vendor.CHATGPT, KEY, fixture.tokens()))
        fixture.authorized = false
        assertFalse(
            fixture.custody.refreshActive(Vendor.CHATGPT, CONNECTION, KEY) { _, _, _ ->
                error("refresh must not leave the bank without active Delegation consent")
            },
        )
        fixture.authorized = true
        assertNotNull(fixture.custody.loadActive(Vendor.CHATGPT, CONNECTION, KEY))
    }

    @Test
    fun `ambiguous refresh fails closed without reusing the prior token`(): Unit = runBlocking {
        val fixture = Fixture().apply { authorized = true }
        assertNotNull(fixture.custody.storeApproved(Vendor.CHATGPT, KEY, fixture.tokens()))
        val failed = runCatching {
            fixture.custody.refreshActive(Vendor.CHATGPT, CONNECTION, KEY) { _, _, _ -> error("transport uncertain") }
        }
        assertEquals("transport uncertain", failed.exceptionOrNull()?.message)
        assertFalse(fixture.custody.refreshActive(Vendor.CHATGPT, CONNECTION, KEY) { _, _, _ -> error("reused token") })
        assertNull(fixture.custody.loadActive(Vendor.CHATGPT, CONNECTION, KEY))
    }

    private class Fixture {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val mapper = ObjectMapper()
        var now: Instant = Instant.parse("2026-11-26T00:00:00Z")
        private val clock = object : Clock() {
            override fun instant(): Instant = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
        }
        val exactKey = GrantKey(PARTY, Vendor.CHATGPT.bankClientId, KEY, CONNECTION)
        var authorized = false
        var capability = "ACCOUNT_READ_BALANCES"
        var lastLookup: GrantKey? = null
        val store = MemoryStore()
        val custody = HostedBankCustody(
            HostedBankJwtVerifier(mapper, mapOf("fixture" to (keys.public as RSAPublicKey)), clock),
            object : CustomerGrantSource {
                override fun resolve(key: GrantKey): List<CustomerGrant> {
                    lastLookup = key
                    return if (authorized && key == exactKey) {
                        listOf(
                            CustomerGrant(UUID.randomUUID(), UUID.randomUUID(), setOf(capability), null),
                        )
                    } else {
                        emptyList()
                    }
                }
            },
            FixtureProtector(),
            store,
            mapper,
            clock,
        )

        fun tokens(audience: String = "openbank-mcp-service", refreshSid: String = "session", revision: Int = 1) =
            mapper.createObjectNode().apply {
                put("token_type", "DPoP")
                put("access_token", token("DPoP", "session", audience, revision))
                put(
                    "refresh_token",
                    token("Refresh", refreshSid, "https://kc.open-bank.tech/realms/openbank-customers", revision),
                )
            }

        private fun token(type: String, session: String, audience: String, revision: Int): String =
            JsonWebSignature().apply {
                algorithmHeaderValue = "RS256"
                keyIdHeaderValue = "fixture"
                key = keys.private
                payload = mapper.writeValueAsString(
                    mapOf(
                        "iss" to "https://kc.open-bank.tech/realms/openbank-customers",
                        "azp" to Vendor.CHATGPT.bankClientId,
                        "aud" to audience,
                        "sub" to "fixture-user",
                        "jti" to "fixture-revision-$revision",
                        "reuse_id" to "fixture-family",
                        "sid" to session,
                        "typ" to type,
                        "iat" to clock.instant().epochSecond,
                        "exp" to clock.instant().plusSeconds(if (type == "Refresh") 600 else 60).epochSecond,
                        "party_id" to PARTY.toString(),
                        "openbank_mcp_connection_id" to CONNECTION.toString(),
                        "cnf" to mapOf("jkt" to KEY),
                    ),
                )
            }.compactSerialization
    }

    private class MemoryStore : HostedBankGrantStore {
        private val rows = mutableMapOf<Pair<Vendor, UUID>, String>()
        private val rotation = mutableMapOf<Pair<Vendor, UUID>, UUID>()
        override suspend fun put(vendor: Vendor, connectionId: UUID, sealed: String, expires: Instant): Boolean =
            rows.putIfAbsent(vendor to connectionId, sealed) == null

        override suspend fun get(vendor: Vendor, connectionId: UUID): String? =
            rows[vendor to connectionId].takeIf { (vendor to connectionId) !in rotation }

        override suspend fun erase(vendor: Vendor, connectionId: UUID) {
            rows.remove(vendor to connectionId)
            rotation.remove(vendor to connectionId)
        }

        override suspend fun claim(vendor: Vendor, connectionId: UUID, operation: UUID): String? {
            val row = get(vendor, connectionId) ?: return null
            rotation[vendor to connectionId] = operation
            return row
        }

        override suspend fun replace(
            vendor: Vendor,
            connectionId: UUID,
            operation: UUID,
            sealed: String,
            expires: Instant,
        ): Boolean {
            if (!rotation.remove(vendor to connectionId, operation)) return false
            rows[vendor to connectionId] = sealed
            return true
        }

        override suspend fun release(vendor: Vendor, connectionId: UUID, operation: UUID) {
            rotation.remove(vendor to connectionId, operation)
        }

        override suspend fun discard(vendor: Vendor, connectionId: UUID, operation: UUID) {
            if (rotation.remove(vendor to connectionId, operation)) rows.remove(vendor to connectionId)
        }
    }

    private class FixtureProtector : FieldProtector {
        private lateinit var original: ByteArray
        private lateinit var associated: ByteArray
        override fun encrypt(plaintext: ByteArray, aad: ByteArray?): String {
            original = plaintext.copyOf()
            associated = requireNotNull(aad).copyOf()
            return "vault:v1:fixture"
        }

        override fun decrypt(ciphertext: String, aad: ByteArray?): ByteArray {
            check(ciphertext == "vault:v1:fixture" && associated.contentEquals(aad))
            return original.copyOf()
        }

        override fun rewrap(ciphertext: String, aad: ByteArray?): String = error("Unexpected rewrap")
        override fun tokenize(value: String): String = error("Unexpected tokenize")
        override fun tokenize(value: String, domain: String): String = error("Unexpected tokenize")
    }

    private companion object {
        val PARTY: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val CONNECTION: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val KEY = "A".repeat(43)
    }
}
