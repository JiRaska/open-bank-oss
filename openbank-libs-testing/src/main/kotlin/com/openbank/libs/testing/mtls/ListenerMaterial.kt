// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.mtls

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Ephemeral key material for [ListenerAuthParityConformance] (#12511): a server certificate for
 * the 8443 listener, one client certificate the listener trusts (the stand-in for a fleet-CA
 * workload certificate), and an RSA key pair whose public half the service's OIDC bearer
 * mechanism verifies locally — so the bearer is a REAL signed token, checked by the production
 * mechanism, with no identity provider running. Nothing is committed; everything lives in a temp
 * directory deleted at [stop].
 *
 * A test profile's resources load in a different classloader from the test class, so the
 * directory and password travel through system properties, never a companion field.
 */
class ListenerMaterial : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> {
        val dir = Files.createTempDirectory("listener-parity-")
        val password = UUID.randomUUID().toString()
        generate(dir.resolve("server.p12"), "server", "CN=localhost", password)
        generate(dir.resolve("client.p12"), "client", "CN=fleet-workload-test", password)
        pem(dir, "server", password, "tls")
        pem(dir, "client", password, "client")
        val signing = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }.generateKeyPair()
        Files.write(dir.resolve("signing.key"), signing.private.encoded)
        System.setProperty(DIR_PROPERTY, dir.toString())
        System.setProperty(PASSWORD_PROPERTY, password)
        // Also under `%test.` for the reason ProductionListenerProfile gives.
        return mapOf(
            "quarkus.http.ssl.certificate.files" to dir.resolve("tls.crt").toString(),
            "quarkus.http.ssl.certificate.key-files" to dir.resolve("tls.key").toString(),
            "quarkus.http.ssl.certificate.trust-store-files" to dir.resolve("client.crt").toString(),
            "quarkus.http.ssl.protocols" to "TLSv1.3",
            "quarkus.http.test-ssl-port" to "0",
            // The production bearer mechanism, verifying against a local public key instead of
            // the realm's JWKS. auth-server-url is blanked (empty = unset in SmallRye): while it is
            // set, the tenant still dials the realm at the first request even with public-key.
            // (quarkus.oidc.enabled is build time: ProductionListenerProfile sets it.)
            "quarkus.oidc.auth-server-url" to "",
            "quarkus.oidc.public-key" to Base64.getEncoder().encodeToString(signing.public.encoded),
            "quarkus.oidc.token.issuer" to ISSUER,
        ).flatMap { (k, v) -> listOf(k to v, "%test.$k" to v) }.toMap()
    }

    override fun stop() {
        System.getProperty(DIR_PROPERTY)?.let { d ->
            Files.walk(Path.of(d)).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    companion object {
        const val ISSUER = "https://listener-parity.openbank.test"
        private const val DIR_PROPERTY = "openbank.test.listener-parity.dir"
        private const val PASSWORD_PROPERTY = "openbank.test.listener-parity.password"
        private const val RSA_BITS = 2048
        private const val LINE = 64
        private const val NEWLINE: Byte = 10
        private const val TOKEN_TTL_SECONDS = 300L

        private val dir: Path get() = Path.of(System.getProperty(DIR_PROPERTY))
        private val password: String get() = System.getProperty(PASSWORD_PROPERTY)

        /** A TLS 1.3 client context trusting the test server; presents the client certificate only when asked to. */
        fun sslContext(withClientCertificate: Boolean): SSLContext {
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setCertificateEntry("server", load("server.p12").getCertificate("server"))
                },
            )
            val keys = if (withClientCertificate) {
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    .apply { init(load("client.p12"), password.toCharArray()) }.keyManagers
            } else {
                null
            }
            return SSLContext.getInstance("TLSv1.3").apply { init(keys, trust.trustManagers, null) }
        }

        /** An RS256 access token the service's OIDC mechanism accepts: right issuer, right key, not expired. */
        fun bearer(roles: List<String> = listOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")): String {
            val now = Instant.now().epochSecond
            val header = """{"alg":"RS256","typ":"JWT"}"""
            val rolesJson = roles.joinToString(",") { "\"$it\"" }
            val claims =
                """{"iss":"$ISSUER","sub":"listener-parity-test","preferred_username":"listener-parity-test",""" +
                    """"iat":$now,"exp":${now + TOKEN_TTL_SECONDS},"realm_access":{"roles":[$rolesJson]},"groups":[$rolesJson]}"""
            val enc = Base64.getUrlEncoder().withoutPadding()
            val signingInput = enc.encodeToString(header.toByteArray()) + "." + enc.encodeToString(claims.toByteArray())
            val key = KeyFactory.getInstance(
                "RSA",
            ).generatePrivate(PKCS8EncodedKeySpec(Files.readAllBytes(dir.resolve("signing.key"))))
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(key)
                update(signingInput.toByteArray())
            }.sign()
            return signingInput + "." + enc.encodeToString(signature)
        }

        private fun load(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(dir.resolve(name)).use { load(it, password.toCharArray()) }
        }

        private fun generate(store: Path, alias: String, dname: String, password: String) {
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", dname, "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", password, "-noprompt",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "Ephemeral TLS certificate generation failed: $output" }
        }

        private fun pem(dir: Path, alias: String, password: String, prefix: String) {
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(dir.resolve("$alias.p12")).use { store.load(it, password.toCharArray()) }
            write(dir.resolve("$prefix.crt"), "CERTIFICATE", store.getCertificate(alias).encoded)
            write(dir.resolve("$prefix.key"), "PRIVATE KEY", store.getKey(alias, password.toCharArray()).encoded)
        }

        private fun write(path: Path, label: String, bytes: ByteArray) {
            val encoded = Base64.getMimeEncoder(LINE, byteArrayOf(NEWLINE)).encodeToString(bytes)
            Files.writeString(path, "-----BEGIN $label-----\n$encoded\n-----END $label-----\n")
        }
    }
}
