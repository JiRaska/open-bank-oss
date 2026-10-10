// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.PostgresTestResource
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
import java.util.Comparator
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Measures the real HTTPS listener with the build-time client-auth value read from %prod YAML. */
@QuarkusTest
@TestProfile(ConsentListenerAuthMeasurementIT.ListenerProfile::class)
class ConsentListenerAuthMeasurementIT {
    @ConfigProperty(name = "quarkus.http.test-ssl-port")
    lateinit var httpsPort: String

    @ConfigProperty(name = "listener.test.api-token")
    lateinit var apiToken: String

    @Test
    fun `TLS refuses a caller without a certificate`() {
        assertThatThrownBy { send(withCertificate = false, bearer = null) }
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `trusted certificate without a bearer is unauthorized`() {
        val response = send(withCertificate = true, bearer = null)
        assertThat(response.statusCode()).isEqualTo(401)
    }

    @Test
    fun `trusted certificate and valid API bearer pass authentication`() {
        val response = send(withCertificate = true, bearer = apiToken)
        assertThat(response.statusCode()).isEqualTo(404)
        // A route miss is also 404. The consent-specific application error proves the request
        // reached ConsentResource and its repository lookup after bearer authentication.
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"")
            .contains("Consent not found: 00000000-0000-0000-0000-000000000001")
    }

    private fun send(withCertificate: Boolean, bearer: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(
            URI.create("https://localhost:$httpsPort/api/v1/consents/00000000-0000-0000-0000-000000000001"),
        ).timeout(Duration.ofSeconds(10)).apply {
            if (bearer != null) header("Authorization", "Bearer $bearer")
        }.GET().build()
        return HttpClient.newBuilder().sslContext(ListenerMaterial().sslContext(withCertificate)).build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }

    class ListenerProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(ListenerMaterial::class.java))

        override fun getConfigOverrides(): Map<String, String> = productionListenerConfig() +
            mapOf("quarkus.oidc.enabled" to "true")

        private fun productionListenerConfig(): Map<String, String> {
            val source = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("openbank-consent-service/src/main/resources/application.yaml") }
                .first { Files.isRegularFile(it) }

            @Suppress("UNCHECKED_CAST")
            val yaml = Yaml().load<Map<String, Any>>(Files.readString(source))
            val prod = yaml.getValue("%prod") as Map<String, Any>
            val quarkus = prod.getValue("quarkus") as Map<String, Any>
            val http = quarkus.getValue("http") as Map<String, Any>
            val ssl = http.getValue("ssl") as Map<String, Any>
            // Missing permission is intentionally allowed in the profile setup so the same
            // listener test can demonstrate the pre-fix 403 on the parent commit.
            val auth = http["auth"] as? Map<*, *>
            val permissions = auth?.get("permission") as? Map<*, *>
            val bearerOnly = permissions?.get("bearer-only") as? Map<*, *>
            val clientAuth = ssl.getValue("client-auth").toString().also { check(it == "required") }
            val bearerProperties = bearerOnly?.let {
                mapOf(
                    "quarkus.http.auth.permission.bearer-only.paths" to requireNotNull(it["paths"]).toString(),
                    "quarkus.http.auth.permission.bearer-only.policy" to requireNotNull(it["policy"]).toString(),
                    "quarkus.http.auth.permission.bearer-only.auth-mechanism" to
                        requireNotNull(it["auth-mechanism"]).toString(),
                )
            }.orEmpty()
            return mapOf("quarkus.http.ssl.client-auth" to clientAuth) + bearerProperties
        }
    }
}

/** Ephemeral local TLS and JWT material. No credential or certificate enters the repository. */
class ListenerMaterial : QuarkusTestResourceLifecycleManager {
    private val database = PostgresTestResource()

    override fun start(): Map<String, String> {
        val dir = Files.createTempDirectory("consent-listener-")
        val password = UUID.randomUUID().toString()
        generate(dir.resolve("server.p12"), "server", password)
        generate(dir.resolve("client.p12"), "client", password)
        pem(dir, "server", password, "tls")
        pem(dir, "client", password, "client")
        System.setProperty(DIR_PROPERTY, dir.toString())
        System.setProperty(PASSWORD_PROPERTY, password)

        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val expiry = Instant.now().epochSecond + 600
        val claims = """
            {"iss":"urn:openbank:listener-check","aud":"openbank-listener-check",
             "sub":"api-client","groups":["ROLE_API"],"iat":${expiry - 3600},"exp":$expiry}
        """.trimIndent()
        val input = "$header.${encoder.encodeToString(claims.toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private)
            update(input.toByteArray())
        }.sign()
        val token = "$input.${encoder.encodeToString(signature)}"
        return database.start() + mapOf(
            "quarkus.http.ssl.certificate.files" to dir.resolve("tls.crt").toString(),
            "quarkus.http.ssl.certificate.key-files" to dir.resolve("tls.key").toString(),
            "quarkus.http.ssl.certificate.trust-store-files" to dir.resolve("client.crt").toString(),
            "quarkus.http.ssl.protocols" to "TLSv1.3",
            "quarkus.http.test-ssl-port" to "0",
            "quarkus.http.insecure-requests" to "enabled",
            "quarkus.oidc.tenant-enabled" to "true",
            "quarkus.oidc.public-key" to Base64.getEncoder().encodeToString(keys.public.encoded),
            "quarkus.oidc.auth-server-url" to "",
            "quarkus.oidc.discovery-enabled" to "false",
            "quarkus.oidc.token.issuer" to "urn:openbank:listener-check",
            "quarkus.oidc.token.audience" to "openbank-listener-check",
            "quarkus.oidc.roles.role-claim-path" to "groups",
            "quarkus.oidc-client.client-enabled" to "false",
            "quarkus.scheduler.enabled" to "false",
            "kafka.bootstrap.servers" to "127.0.0.1:1",
            "mp.messaging.outgoing.consent-events-out.bootstrap.servers" to "127.0.0.1:1",
            "quarkus.redis.hosts" to "redis://127.0.0.1:1",
            "openbank.outbox.dispatch-enabled" to "false",
            "listener.test.api-token" to token,
        )
    }

    override fun stop() {
        database.stop()
        System.getProperty(DIR_PROPERTY)?.let { directory ->
            Files.walk(Path.of(directory)).use { files ->
                files.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        System.clearProperty(DIR_PROPERTY)
        System.clearProperty(PASSWORD_PROPERTY)
    }

    fun sslContext(withClientCertificate: Boolean): SSLContext {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("server", load("server.p12").getCertificate("server"))
            },
        )
        val managers = if (withClientCertificate) {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(load("client.p12"), password().toCharArray())
            }.keyManagers
        } else {
            null
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(managers, trust.trustManagers, null) }
    }

    private fun load(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        Files.newInputStream(Path.of(System.getProperty(DIR_PROPERTY), name)).use {
            load(it, password().toCharArray())
        }
    }

    private fun password(): String = System.getProperty(PASSWORD_PROPERTY)

    private fun generate(store: Path, alias: String, password: String) {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-validity", "1", "-storetype", "PKCS12", "-keystore", store.toString(),
            "-storepass", password, "-noprompt",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Ephemeral TLS generation failed: $output" }
    }

    private fun pem(dir: Path, alias: String, password: String, prefix: String) {
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(dir.resolve("$alias.p12")).use { store.load(it, password.toCharArray()) }
        write(dir.resolve("$prefix.crt"), "CERTIFICATE", store.getCertificate(alias).encoded)
        write(dir.resolve("$prefix.key"), "PRIVATE KEY", store.getKey(alias, password.toCharArray()).encoded)
    }

    private fun write(path: Path, label: String, bytes: ByteArray) {
        val value = Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)
        Files.writeString(path, "-----BEGIN $label-----\n$value\n-----END $label-----\n")
    }

    companion object {
        private const val DIR_PROPERTY = "openbank.test.consent-listener.dir"
        private const val PASSWORD_PROPERTY = "openbank.test.consent-listener.password"
    }
}
