// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.OpenBaoTokenSource
import com.openbank.libs.security.OpenBaoTransport
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

class HostedBankTransitProofTest {
    @Test
    fun `vendor specific assertion and DPoP use separate pinned Transit keys`(): Unit = runBlocking {
        val mapper = ObjectMapper()
        val keyPairs = Vendor.entries.flatMap { vendor ->
            listOf("assertion", "dpop").map { purpose -> "${vendor.bankClientId}-$purpose" to newKey() }
        }.toMap()
        val wrongKey = AtomicBoolean()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/transit/sign/") { exchange ->
            exchange.use {
                val keyName = exchange.requestURI.path.substringAfterLast('/')
                val payload = mapper.readTree(exchange.requestBody)
                assertEquals("jws", payload.path("marshaling_algorithm").asText())
                assertEquals("sha2-256", payload.path("hash_algorithm").asText())
                assertEquals(KEY_VERSION, payload.path("key_version").asInt())
                val pair = if (wrongKey.get()) keyPairs.values.last() else keyPairs.getValue(keyName)
                val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
                    initSign(pair.private)
                    update(Base64.getDecoder().decode(payload.path("input").asText()))
                    Base64.getUrlEncoder().encodeToString(sign())
                }
                val reply = mapper.writeValueAsBytes(
                    mapOf("data" to mapOf("signature" to "vault:v$KEY_VERSION:$signature")),
                )
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.write(reply)
            }
        }
        server.start()
        try {
            val signin = HostedBankTransitProof(
                OpenBaoTransport(
                    URI.create("http://127.0.0.1:${server.address.port}"),
                    allowInsecureHttpForTests = true,
                ),
                OpenBaoTokenSource { "test-workload-token" },
                mapper,
                Vendor.entries.associateWith {
                    HostedBankTransitProof.VendorKeys(
                        HostedBankTransitProof.Key(
                            KEY_VERSION,
                            keyPairs.getValue("${it.bankClientId}-assertion").public as ECPublicKey,
                        ),
                        HostedBankTransitProof.Key(
                            KEY_VERSION,
                            keyPairs.getValue("${it.bankClientId}-dpop").public as ECPublicKey,
                        ),
                    )
                },
            )
            val issuer = URI.create("https://kc.open-bank.tech/realms/openbank-customers")
            val par = URI.create("$issuer/protocol/openid-connect/ext/par/request")
            verifyProofs(signin, mapper, issuer, par)
            verifyUnsafeInputsRefused(signin, issuer, par, wrongKey)
        } finally {
            server.stop(0)
        }
    }

    private suspend fun verifyUnsafeInputsRefused(
        signin: HostedBankTransitProof,
        issuer: URI,
        par: URI,
        wrongKey: AtomicBoolean,
    ) {
        wrongKey.set(true)
        val refusal = runCatching { signin.sign(Vendor.CHATGPT, issuer, par) }
        assertEquals("Bank Transit signature did not match its public pin", refusal.exceptionOrNull()?.message)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { signin.sign(Vendor.CHATGPT, issuer, URI.create("https://outbound.invalid/")) }
        }
    }

    private suspend fun verifyProofs(signin: HostedBankTransitProof, mapper: ObjectMapper, issuer: URI, par: URI) {
        val chatgpt = signin.sign(Vendor.CHATGPT, issuer, par)
        val claude = signin.sign(Vendor.CLAUDE, issuer, par)
        assertNotEquals(chatgpt.dpopThumbprint, claude.dpopThumbprint)
        val documents = listOf(Vendor.CHATGPT to chatgpt, Vendor.CLAUDE to claude)
        for ((vendor, proof) in documents) {
            val assertion = claims(mapper, proof.clientAssertion)
            val dpop = claims(mapper, proof.dpopProof)
            assertEquals(vendor.bankClientId, assertion.path("sub").asText())
            assertEquals(vendor.bankClientId, assertion.path("iss").asText())
            assertEquals(issuer.toString(), assertion.path("aud").asText())
            assertEquals(par.toString(), dpop.path("htu").asText())
            assertEquals("POST", dpop.path("htm").asText())
            assertNotEquals(assertion.path("jti").asText(), dpop.path("jti").asText())
        }
        val exchangeProof = signin.signToken(Vendor.CHATGPT, "bank-nonce")
        val tokenDpop = claims(mapper, exchangeProof.dpopProof)
        assertEquals("$issuer/protocol/openid-connect/token", tokenDpop.path("htu").asText())
        assertEquals("bank-nonce", tokenDpop.path("nonce").asText())
        assertEquals(chatgpt.dpopThumbprint, exchangeProof.dpopThumbprint)
        assertEquals(issuer.toString(), claims(mapper, exchangeProof.clientAssertion).path("aud").asText())
        val resource = claims(mapper, signin.signResource(Vendor.CHATGPT, "bank-token", "resource.nonce"))
        assertEquals(HostedOAuthAdmission.RESOURCE, resource.path("htu").asText())
        assertEquals("resource.nonce", resource.path("nonce").asText())
        assertEquals(HostedOAuthStart.digest("bank-token"), resource.path("ath").asText())
        assertNotEquals(tokenDpop.path("jti").asText(), resource.path("jti").asText())
    }

    private fun claims(mapper: ObjectMapper, jwt: String) = mapper.readTree(
        Base64.getUrlDecoder().decode(jwt.split('.')[1]),
    )

    private fun newKey(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private companion object {
        const val KEY_VERSION = 2
    }
}
