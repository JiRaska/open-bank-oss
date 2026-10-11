// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Listener parity for standing-order API (#12511): the image serves the same routes on the
 * plain HTTP port (8121, kept by `insecure-requests: enabled` for probes and same-cluster callers)
 * and on the mTLS listener (8443, `client-auth: required`). Neither listener may be
 * a way around authentication:
 *
 *  - 8443 refuses the TLS handshake to a client with no certificate — before any route runs;
 *  - 8443 with a trusted client certificate but no bearer is still 401: the certificate is
 *    transport authentication, never a substitute for the OIDC identity OPA decides on;
 *  - 8121 with no bearer is 401 — the same route, the same refusal.
 *
 * Runs the production listener shape: `client-auth: required` is BUILD-TIME, so the test profile
 * reads `%prod` from application.yaml and rebuilds the app with those exact auth values. Key
 * material is ephemeral (keytool), never committed.
 */
@QuarkusTest
@TestProfile(ListenerAuthParityIT.MtlsListenerProfile::class)
@QuarkusTestResource(StandingOrderBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class ListenerAuthParityIT {

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var httpPort: String

    @ConfigProperty(name = "quarkus.http.test-ssl-port")
    lateinit var httpsPort: String

    @ConfigProperty(name = "standingorder.test.bearer-token")
    lateinit var bearerToken: String

    private fun standingOrder(scheme: String, port: String) = HttpRequest.newBuilder(
        URI.create("$scheme://localhost:$port$PATH"),
    ).timeout(Duration.ofSeconds(10)).GET().build()

    @Test
    fun `the mTLS listener refuses a client that presents no certificate`() {
        val noCert = HttpClient.newBuilder().sslContext(sslContext(withClientCertificate = false)).build()
        assertThatThrownBy { noCert.send(standingOrder("https", httpsPort), HttpResponse.BodyHandlers.ofString()) }
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a trusted client certificate without a bearer is still 401 on the mTLS listener`() {
        val withCert = HttpClient.newBuilder().sslContext(sslContext(withClientCertificate = true)).build()
        val response = withCert.send(standingOrder("https", httpsPort), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED)
    }

    @Test
    fun `the plain HTTP listener refuses the same route without a bearer`() {
        val response = HttpClient.newHttpClient().send(
            standingOrder("http", httpPort),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED)
    }

    @Test
    fun `a trusted client certificate and valid bearer reach the standing order handler`() {
        val withCert = HttpClient.newBuilder().sslContext(sslContext(withClientCertificate = true)).build()
        val request = HttpRequest.newBuilder(URI.create("https://localhost:$httpsPort$PATH"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $bearerToken")
            .GET().build()
        // The collection handler returns an empty JSON array from the fresh Postgres fixture.
        // A generic router 404 would not prove the bearer reached a served route.
        val response = withCert.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(OK)
        assertThat(response.body()).isEqualTo("[]")
    }

    private fun sslContext(withClientCertificate: Boolean): SSLContext {
        val material = MtlsMaterial.current()
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("server", material.load("server.p12").getCertificate("server"))
            },
        )
        val keys = if (withClientCertificate) {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(material.load("client.p12"), material.password.toCharArray()) }.keyManagers
        } else {
            null
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(keys, trust.trustManagers, null) }
    }

    class MtlsListenerProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> {
            val source = javaClass.classLoader.getResourceAsStream("application.yaml")
                ?: error("standing-order application.yaml is missing")
            val yaml = source.use { Yaml().load<Map<String, Any>>(it) }
            val prod = yaml["%prod"] as Map<*, *>
            val quarkus = prod["quarkus"] as Map<*, *>
            val http = quarkus["http"] as Map<*, *>
            val ssl = http["ssl"] as Map<*, *>
            val auth = http["auth"] as Map<*, *>
            val permission = auth["permission"] as Map<*, *>
            val bearer = permission["bearer-only"] as Map<*, *>
            check(ssl["client-auth"] == "required")
            check(bearer["paths"] == "/api/*")
            check(bearer["policy"] == "permit")
            check(bearer["auth-mechanism"] == "bearer")
            return mapOf(
                "quarkus.http.ssl.client-auth" to ssl["client-auth"]!!.toString(),
                "quarkus.http.insecure-requests" to http["insecure-requests"]!!.toString(),
                "quarkus.http.auth.permission.bearer-only.paths" to bearer["paths"]!!.toString(),
                "quarkus.http.auth.permission.bearer-only.policy" to bearer["policy"]!!.toString(),
                "quarkus.http.auth.permission.bearer-only.auth-mechanism" to
                    bearer["auth-mechanism"]!!.toString(),
                "quarkus.oidc.enabled" to "true",
                "quarkus.oidc.auth-server-url" to "",
                "quarkus.oidc.discovery-enabled" to "false",
                "quarkus.oidc.jwks-path" to "protocol/openid-connect/certs",
            )
        }

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(MtlsMaterial::class.java))
    }

    /** Ephemeral server and client key pairs; the server trusts exactly the client certificate. */
    class MtlsMaterial : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val dir = Files.createTempDirectory("standingorder-mtls-")
            val password = UUID.randomUUID().toString()
            generate(dir.resolve("server.p12"), "server", "CN=localhost", password)
            generate(dir.resolve("client.p12"), "client", "CN=standingorder-test", password)
            pem(dir, "server", password, "tls")
            pem(dir, "client", password, "client")
            // A profile's resources run in a different classloader from the test class, so the
            // location travels through system properties rather than a companion field.
            System.setProperty(DIR_PROPERTY, dir.toString())
            System.setProperty(PASSWORD_PROPERTY, password)
            return mapOf(
                "quarkus.http.ssl.certificate.files" to dir.resolve("tls.crt").toString(),
                "quarkus.http.ssl.certificate.key-files" to dir.resolve("tls.key").toString(),
                "quarkus.http.ssl.certificate.trust-store-files" to dir.resolve("client.crt").toString(),
                "quarkus.http.ssl.protocols" to "TLSv1.3",
                "quarkus.http.test-ssl-port" to "0",
                "quarkus.http.insecure-requests" to "enabled",
            ) + bearerFixture()
        }

        private fun bearerFixture(): Map<String, String> {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
            val now = Instant.now().epochSecond
            val claims = """
                {"iss":"urn:openbank:standingorder-listener-test","aud":"standingorder-listener-test",
                 "sub":"approver","groups":["ROLE_API"],"iat":$now,"exp":${now + 300}}
            """.trimIndent()
            val input = "$header.${encoder.encodeToString(claims.toByteArray())}"
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keys.private)
                update(input.toByteArray())
            }.sign()
            return mapOf(
                "quarkus.oidc.public-key" to Base64.getEncoder().encodeToString(keys.public.encoded),
                "quarkus.oidc.tenant-enabled" to "true",
                "quarkus.oidc.auth-server-url" to "",
                "quarkus.oidc.discovery-enabled" to "false",
                "quarkus.oidc.token.issuer" to "urn:openbank:standingorder-listener-test",
                "quarkus.oidc.token.audience" to "standingorder-listener-test",
                "quarkus.oidc.roles.role-claim-path" to "groups",
                "standingorder.test.bearer-token" to "$input.${encoder.encodeToString(signature)}",
            )
        }

        override fun stop() {
            System.getProperty(DIR_PROPERTY)?.let { d ->
                Files.walk(Path.of(d)).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }

        val password: String get() = System.getProperty(PASSWORD_PROPERTY)

        fun load(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(Path.of(System.getProperty(DIR_PROPERTY), name)).use {
                load(it, password.toCharArray())
            }
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

        companion object {
            private const val DIR_PROPERTY = "openbank.test.standingorder-mtls.dir"
            private const val PASSWORD_PROPERTY = "openbank.test.standingorder-mtls.password"
            private const val LINE = 64
            private const val NEWLINE: Byte = 10

            fun current() = MtlsMaterial()
        }
    }

    private companion object {
        const val PATH = "/api/v1/standing-orders"
        const val UNAUTHORIZED = 401
        const val OK = 200
    }
}
