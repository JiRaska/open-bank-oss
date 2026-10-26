// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.e2e

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jose4j.base64url.Base64Url
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.keys.EllipticCurves
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.BitSet
import java.util.zip.Inflater

/**
 * EUDI wallet-side and bank-side key material plus SD-JWT VC / proof builders for the pid E2E
 * suite. Everything here signs for real with JCA keys — the suite never stubs the cryptography:
 *
 * - [bankIssuer] — the bank's EC P-256 PID issuer key (delivered into the real
 *   `openbank.pid.eudi.issuer.signing-key-jwk` config via the WireMock test-resource config map,
 *   so EudiIssuerKey signs credentials, status-list tokens and Request Objects for real);
 * - [walletA] — a wallet issuer trusted via the STATIC layer (trusted-issuers-json);
 * - [walletB] — a wallet issuer trusted only via the DYNAMIC eIDAS Trusted List (WireMock);
 * - [walletC] — a wallet issuer that is trusted NOWHERE (fail-closed oracle);
 * - [anchorKey] — the eIDAS scheme operator's EC P-256 trust anchor the signed list verifies against;
 * - [holder] — the wallet holder key: signs key-binding JWTs and OpenID4VCI proofs of possession,
 *   and is bound into issued credentials via `cnf.jwk`.
 *
 * The material is persisted to a well-known temp file on FIRST generation and re-read on every
 * later class load. That is load-bearing, not a convenience: Quarkus loads the test profile and
 * the test-resource lifecycle in a different classloader phase than the test classes, so a plain
 * companion object would initialise TWICE with DIFFERENT random keys — the app would verify
 * signatures against a different key than the one the tests sign with (observed live: identical
 * `kid`, mismatched key material, every presentation answered "issuer signature invalid").
 */
object PidE2EKeys {
    const val BANK_ISSUER_ID = "https://issuer-e2e.openbank.test"
    const val WALLET_A_ISS = "did:e2e:wallet-a"
    const val WALLET_B_ISS = "did:e2e:wallet-b"
    const val WALLET_C_ISS = "did:e2e:wallet-c"
    const val PID_VCT = "eu.europa.ec.eudi.pid.1"

    private val mapper = ObjectMapper()
    private val secureRandom = SecureRandom()
    private val lock = Any()

    val bankIssuer: PublicJsonWebKey
    val walletA: PublicJsonWebKey
    val walletB: PublicJsonWebKey
    val walletC: PublicJsonWebKey
    val holder: PublicJsonWebKey

    /** The eIDAS scheme operator's EC P-256 anchor key — the trust root the list verifies against. */
    val anchorKey: PublicJsonWebKey

    init {
        val material = loadOrGenerate()
        bankIssuer = parse(material.bank)
        walletA = parse(material.walletA)
        walletB = parse(material.walletB)
        walletC = parse(material.walletC)
        holder = parse(material.holder)
        anchorKey = parse(material.anchor)
    }

    /** The bank issuer signing JWK with the PRIVATE half, as the config property consumes it. */
    val bankIssuerPrivateJwkJson: String
        get() = bankIssuer.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE)

    fun anchorJwksJson(): String = """{"keys":[${publicJwkJson(anchorKey)}]}"""

    /** Static trust layer: wallet A and the bank's own issuer key (so a bank-issued, later-revoked
     *  credential can be presented past the trust gate straight into the revocation gate). Wallet B
     *  arrives exclusively through the signed Trusted List. */
    val staticTrustJson: String
        get() = """[{"iss":"$WALLET_A_ISS","jwks":{"keys":[${publicJwkJson(walletA)}]}},""" +
            """{"iss":"$BANK_ISSUER_ID","jwks":{"keys":[${publicJwkJson(bankIssuer)}]}}]"""

    // ── SD-JWT VC building (the wallet side) ────────────────────────────────────

    class WalletCredential(
        /** `<issuer-JWS>~<disclosure>~...` — no key-binding JWT, ready for nonce-less verify. */
        val presentation: String,
        val subjectId: String,
        val statusIndex: Long?,
        val statusUri: String?,
    )

    /**
     * Mint a wallet-issued SD-JWT VC exactly the way the production issuer does
     * ([com.openbank.pid.infrastructure.openid4vci.CredentialIssuerService]): issuer-signed JWS
     * with `_sd` SHA-256 digest array over salted disclosures, holder binding via `cnf.jwk`,
     * optional `status.status_list` claim. With [nonce]/[audience] a key-binding JWT is appended,
     * so the verifier's anti-replay check has something to pin.
     */
    fun walletPresentation(
        issuerKey: PublicJsonWebKey,
        subjectId: String,
        givenName: String = "Tereza",
        familyName: String = "E2eova",
        birthdate: String = "1991-04-17",
        statusIndex: Long? = null,
        statusUri: String? = null,
        expired: Boolean = false,
        nonce: String? = null,
        audience: String? = null,
        issOverride: String? = null,
    ): WalletCredential {
        val disclosures = listOf(
            disclosure("given_name", givenName),
            disclosure("family_name", familyName),
            disclosure("birthdate", birthdate),
        )
        val sha = MessageDigest.getInstance("SHA-256")
        val sd = disclosures.map { Base64Url.encode(sha.digest(it.toByteArray(Charsets.US_ASCII))) }
        val now = Instant.now().epochSecond
        val payload = mapper.createObjectNode().apply {
            put("iss", issOverride ?: issuerKeyToIss(issuerKey))
            put("vct", PID_VCT)
            put("sub", subjectId)
            put("iat", now)
            put("exp", if (expired) now - CREDENTIAL_TTL_SECONDS else now + CREDENTIAL_TTL_SECONDS)
            put("issuing_country", "CZ")
            put("_sd_alg", "sha-256")
            set<JsonNode>(
                "cnf",
                mapper.createObjectNode().set<JsonNode>("jwk", mapper.readTree(publicJwkJson(holder))),
            )
            if (statusIndex != null && statusUri != null) {
                set<JsonNode>(
                    "status",
                    mapper.createObjectNode().set<JsonNode>(
                        "status_list",
                        mapper.createObjectNode().put("idx", statusIndex).put("uri", statusUri),
                    ),
                )
            }
            set<JsonNode>("_sd", mapper.createArrayNode().apply { sd.forEach { add(it) } })
        }
        val issuerJws = sign(payload, issuerKey)
        val kb = if (nonce != null || audience != null) {
            "~" + sign(
                mapper.createObjectNode().apply {
                    put("nonce", nonce)
                    put("aud", audience)
                    put("iat", now)
                },
                holder,
            )
        } else {
            "~"
        }
        return WalletCredential("$issuerJws~${disclosures.joinToString("~")}$kb", subjectId, statusIndex, statusUri)
    }

    /** The OpenID4VCI proof of possession: holder-signed JWT carrying the holder JWK and the nonce. */
    fun proofOfPossession(nonce: String, audience: String = BANK_ISSUER_ID): String {
        val jws = JsonWebSignature()
        jws.payload = mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                put("nonce", nonce)
                put("aud", audience)
                put("iat", Instant.now().epochSecond)
            },
        )
        jws.key = holder.privateKey
        // The endpoint reads the holder key back via getObjectHeaderValue("jwk") (a Map after
        // parsing), so the header must carry the JWK as a plain JSON object — not a JsonWebKey
        // instance, which Jackson would not re-serialize as a JWK document.
        @Suppress("UNCHECKED_CAST")
        val jwkObject = mapper.readValue(publicJwkJson(holder), Map::class.java) as Map<String, Any>
        jws.headers.setObjectHeaderValue("jwk", jwkObject)
        jws.algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
        return jws.compactSerialization
    }

    // ── Status-list token parsing (the relying-party side) ──────────────────────

    class ParsedStatusList(val revokedIndices: Set<Long>, val expiresAt: Long, val ttl: Long)

    /** Verify the published Token Status List against the bank public key and unpack the bits. */
    fun parseStatusListToken(token: String): ParsedStatusList {
        val jws = JsonWebSignature().apply {
            compactSerialization = token
            key = bankIssuer.publicKey
        }
        check(jws.verifySignature()) { "status-list token signature must verify against the bank key" }
        val payload = mapper.readTree(jws.payload)
        // RFC 9597: status_list.lst is a DEFLATE-compressed, base64url-encoded bit array.
        val bits = inflate(Base64Url.decode(payload["status_list"]["lst"].asText()))
        val revoked = generateSequence(0) { it + 1 }.takeWhile { it <= bits.length() }
            .filter { bits.get(it) }.map { it.toLong() }.toSet()
        return ParsedStatusList(revoked, payload["exp"].asLong(), payload["ttl"].asLong())
    }

    private fun inflate(input: ByteArray): BitSet {
        val inflater = Inflater()
        inflater.setInput(input)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(INFLATE_BUF)
        try {
            while (!inflater.finished()) {
                out.write(buf, 0, inflater.inflate(buf))
            }
        } finally {
            inflater.end()
        }
        return BitSet.valueOf(out.toByteArray())
    }

    // ── internals ───────────────────────────────────────────────────────────────

    fun publicJwkJson(key: PublicJsonWebKey): String = key.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)

    fun issuerKeyToIss(key: PublicJsonWebKey): String = when (key) {
        bankIssuer -> BANK_ISSUER_ID
        walletA -> WALLET_A_ISS
        walletB -> WALLET_B_ISS
        walletC -> WALLET_C_ISS
        else -> error("unknown issuer key ${key.keyId}")
    }

    private fun disclosure(name: String, value: String): String {
        val salt = Base64Url.encode(ByteArray(16).also { secureRandom.nextBytes(it) })
        val arr = mapper.createArrayNode().apply {
            add(salt)
            add(name)
            add(value)
        }
        return Base64Url.encode(mapper.writeValueAsString(arr).toByteArray(Charsets.UTF_8))
    }

    private fun sign(payload: JsonNode, key: PublicJsonWebKey): String = JsonWebSignature().apply {
        this.payload = mapper.writeValueAsString(payload)
        this.key = key.privateKey
        keyIdHeaderValue = key.keyId
        algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
    }.compactSerialization

    // ── cross-classloader key material persistence ──────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private class KeyMaterial(
        var bank: String = "",
        var walletA: String = "",
        var walletB: String = "",
        var walletC: String = "",
        var holder: String = "",
        var anchor: String = "",
    )

    private fun parse(privateJwkJson: String): PublicJsonWebKey = PublicJsonWebKey.Factory.newPublicJwk(privateJwkJson)

    /**
     * Load the key material from the well-known temp file, generating + persisting it on first
     * use. Synchronised on a JVM-wide monitor because two Gradle workers can share the machine;
     * generation is random-per-file, so first writer wins and everyone else re-reads.
     */
    private fun loadOrGenerate(): KeyMaterial = synchronized(lock) {
        val file = File(System.getProperty("java.io.tmpdir"), KEY_FILE_NAME)
        runCatching {
            if (file.isFile) {
                val loaded = mapper.readValue(file, KeyMaterial::class.java)
                if (loaded.bank.isNotEmpty() && loaded.anchor.isNotEmpty()) return@synchronized loaded
            }
        }
        val material = KeyMaterial(
            bank = newKey("bank-issuer-e2e").toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE),
            walletA = newKey("wallet-a-e2e").toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE),
            walletB = newKey("wallet-b-e2e").toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE),
            walletC = newKey("wallet-c-e2e").toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE),
            holder = newKey("holder-e2e").toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE),
            anchor = newKey("anchor-e2e").toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE),
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(mapper.writeValueAsString(material))
        check(tmp.renameTo(file)) { "failed to install $KEY_FILE_NAME atomically" }
        material
    }

    private fun newKey(keyId: String): PublicJsonWebKey = EcJwkGenerator.generateJwk(EllipticCurves.P256).apply {
        this.keyId = keyId
    }

    private const val CREDENTIAL_TTL_SECONDS = 3600L
    private const val INFLATE_BUF = 1024
    private const val KEY_FILE_NAME = "openbank-pid-e2e-keys.json"
}
